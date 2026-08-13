package com.imin.iminapi.buyer.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerEmailVerificationCodeRepository;
import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.buyer.repository.BuyerPasswordResetTokenRepository;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.buyer.repository.BuyerVerificationAttemptRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The destructive half of buyer account deletion — §7.2, executed by
 * {@link BuyerAccountErasureJob} once the 30-day grace period is up.
 *
 * <h2>Why this fans out over addresses</h2>
 *
 * <p>{@code consumers.normalized_email} is UNIQUE (V47:10), so an account with
 * <i>N</i> verified addresses is <i>N</i> {@code Consumer} rows, each with its
 * own memberships, consent records, suppressions, campaign PII and notify
 * subscriptions behind it. Erasing "the" consumer leaves the other N−1
 * standing, and that is not an Art.17 erasure. Every step below iterates the
 * address <b>set</b>.
 *
 * <h2>Why the org cascade is not reimplemented here</h2>
 *
 * <p>Step 2 hands each membership straight back to
 * {@link DsarService#executeErase} — the same code path an organizer DSAR takes.
 * A second, buyer-flavoured copy of that cascade would drift from the original
 * the first time either changed, and the one that drifts silently is the one
 * that leaves personal data behind.
 *
 * <h2>The ordering constraint that is easy to get wrong</h2>
 *
 * <p>{@code executeErase} deletes the {@code Consumer} when no membership
 * remains — <b>unless</b> a verified {@code buyer_account_emails} row still
 * anchors it (the guard this slice added to {@code DsarService}). During a
 * buyer erasure that guard is still armed while step 2 runs, because the
 * account's own address rows have not been deleted yet, so every Consumer
 * survives step 2 by design. That is exactly why §7.2 has a separate step 5:
 * the consumer sweep must run <b>after</b> step 4 has dropped
 * {@code buyer_account_emails}, at which point nothing anchors them any more.
 * Moving the email delete later, or the consumer sweep earlier, silently leaks
 * a {@code Consumer} row per address.
 *
 * <h2>Tables with no JPA entity</h2>
 *
 * <p>{@code buyer_saved_events}, {@code buyer_notification_preferences} and
 * {@code marketing_optouts} ship as DDL in V85 with no entity yet — their write
 * paths land in R1.4 / R1.6. They are cleared with {@link JdbcTemplate} rather
 * than by inventing entity classes that a later slice would have to reconcile
 * with its own. The statements are plain keyed DELETEs in a batch job, which is
 * the case JDBC is for.
 */
@Service
public class BuyerAccountErasureService {

    private static final Logger log = LoggerFactory.getLogger(BuyerAccountErasureService.class);

    private final BuyerAccountRepository accounts;
    private final BuyerAccountEmailRepository emails;
    private final BuyerSessionRepository sessions;
    private final BuyerIdentityRepository identities;
    private final BuyerEmailVerificationCodeRepository codes;
    private final BuyerPasswordResetTokenRepository resetTokens;
    private final BuyerVerificationAttemptRepository attempts;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final NotifySubscriptionRepository notifySubscriptions;
    private final DsarService dsarService;
    private final AuditLogger auditLogger;
    private final JdbcTemplate jdbc;

    public BuyerAccountErasureService(BuyerAccountRepository accounts,
                                      BuyerAccountEmailRepository emails,
                                      BuyerSessionRepository sessions,
                                      BuyerIdentityRepository identities,
                                      BuyerEmailVerificationCodeRepository codes,
                                      BuyerPasswordResetTokenRepository resetTokens,
                                      BuyerVerificationAttemptRepository attempts,
                                      ConsumerRepository consumers,
                                      MembershipRepository memberships,
                                      NotifySubscriptionRepository notifySubscriptions,
                                      DsarService dsarService,
                                      AuditLogger auditLogger,
                                      JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.emails = emails;
        this.sessions = sessions;
        this.identities = identities;
        this.codes = codes;
        this.resetTokens = resetTokens;
        this.attempts = attempts;
        this.consumers = consumers;
        this.memberships = memberships;
        this.notifySubscriptions = notifySubscriptions;
        this.dsarService = dsarService;
        this.auditLogger = auditLogger;
        this.jdbc = jdbc;
    }

    /** What one account's erasure removed — logged, and asserted in tests. */
    public record ErasureResult(UUID accountId,
                                int addresses,
                                int membershipsErased,
                                Set<UUID> orgs,
                                int consumersDeleted,
                                int notifySubscriptions,
                                boolean tombstoned) {}

    /**
     * Erases one account and everything the platform holds for its addresses.
     *
     * <p>One transaction: an erasure that half-happened is worse than one that
     * did not happen, because the account row would be gone and nothing would
     * ever come back for the rest.
     */
    @Transactional
    public ErasureResult erase(UUID accountId) {
        // ── 1. The address set — verified AND unverified (§7.2 step 1). ─────
        List<String> addressSet = emails.findEmailsByBuyerAccountId(accountId);
        List<String> verified = emails.findVerifiedEmailsByBuyerAccountId(accountId);

        // ── 2. Per verified address: resolve the Consumer, erase every ──────
        //       membership through the existing org-scoped cascade.
        Set<UUID> consumerIds = new LinkedHashSet<>();
        Set<UUID> orgs = new LinkedHashSet<>();
        int membershipsErased = 0;

        for (String address : verified) {
            UUID consumerId = consumers.findByNormalizedEmail(address)
                    .map(c -> c.getConsumerId())
                    .orElse(null);
            if (consumerId == null || !consumerIds.add(consumerId)) continue;

            for (Membership m : new ArrayList<>(memberships.findAllByConsumerId(consumerId))) {
                UUID orgId = m.getOrgId();
                orgs.add(orgId);
                // The org-scoped cascade, reused verbatim: consent records,
                // marketing suppressions, campaign-recipient PII, that org's
                // notify rows, then the membership. Its own Consumer delete is
                // inhibited by the buyer-account anchor guard until step 4 below.
                dsarService.executeErase(orgId, m.getMembershipId(),
                        new AuthPrincipal(null, orgId, UserRole.MEMBER, null));
                membershipsErased++;
            }
        }

        // ── 3. Notify-me subscriptions, EVERY address, ALL orgs (§7.2 step 3).
        // executeErase deliberately removes only the erasing org's rows, and a
        // notify subscription can exist with no membership at all — anyone can
        // ask to be told about a release without ever buying. In a
        // buyer-initiated erasure those rows are squarely the buyer's data.
        int notifyRemoved = addressSet.isEmpty() ? 0
                : notifySubscriptions.deleteByEmailIn(addressSet);

        // ── 4. The buyer tables, in §7.2's order. ───────────────────────────
        // Enumerated even where an FK cascade from buyer_accounts would cover
        // it: the address-keyed ones (marketing_optouts,
        // buyer_verification_attempts) have no FK to cascade through, and an
        // explicit list is the only version of this that can be reviewed
        // against the schema.
        int savedEvents = jdbc.update(
                "delete from buyer_saved_events where buyer_account_id = ?", accountId);
        int prefs = jdbc.update(
                "delete from buyer_notification_preferences where buyer_account_id = ?", accountId);
        identities.deleteByBuyerAccountId(accountId);
        sessions.deleteByBuyerAccountId(accountId);
        codes.deleteByBuyerAccountId(accountId);
        resetTokens.deleteByBuyerAccountId(accountId);

        int attemptRows = 0;
        int optoutRows = 0;
        if (!addressSet.isEmpty()) {
            attemptRows = attempts.deleteByEmailNormalizedIn(addressSet);
            optoutRows = deleteMarketingOptouts(addressSet);
        }

        // buyer_account_emails BEFORE buyer_accounts, and before step 5 — this
        // is the delete that disarms the Consumer-survival guard.
        emails.deleteByBuyerAccountId(accountId);
        accounts.deleteById(accountId);

        // ── 5. Consumers that nothing references any more (§7.2 step 5). ────
        // Now that the verified address rows are gone, the anchor guard in
        // DsarService no longer holds these open. A consumer that picked up a
        // membership in another org in the meantime is left alone: that
        // membership is the other org's record, not this buyer's account.
        int consumersDeleted = 0;
        for (UUID consumerId : consumerIds) {
            if (consumers.countMembershipsByConsumerId(consumerId) == 0) {
                consumers.deleteByConsumerId(consumerId);
                consumersDeleted++;
            }
        }

        // ── 6. Tombstone (§7.2 step 6). ─────────────────────────────────────
        boolean tombstoned = writeTombstone(accountId, orgs, addressSet.size(), membershipsErased);

        ErasureResult result = new ErasureResult(accountId, addressSet.size(), membershipsErased,
                orgs, consumersDeleted, notifyRemoved, tombstoned);

        log.info("[buyer] erased account={} addresses={} memberships={} orgs={} consumers={} "
                        + "notify={} saved={} prefs={} attempts={} optouts={} tombstoned={}",
                accountId, addressSet.size(), membershipsErased, orgs.size(), consumersDeleted,
                notifyRemoved, savedEvents, prefs, attemptRows, optoutRows, tombstoned);

        return result;
    }

    /**
     * {@code marketing_optouts} is keyed by {@code (email_normalized, org_id,
     * channel)} with no account column and no FK, so nothing cascades into it
     * and the address set is the only handle. Written as a JDBC batch-friendly
     * IN list because the table has no entity until R1.6.
     */
    private int deleteMarketingOptouts(List<String> addresses) {
        String placeholders = String.join(",", java.util.Collections.nCopies(addresses.size(), "?"));
        return jdbc.update("delete from marketing_optouts where email_normalized in (" + placeholders + ")",
                addresses.toArray());
    }

    /**
     * The Art.17 tombstone — one row per org that held a copy of this buyer.
     *
     * <p><b>Why per-org and not one platform-level row.</b>
     * {@code audit_logs.org_id} is NOT NULL (V21:3) and the only index and every
     * reader are org-scoped, so there is nowhere for an org-less row to live
     * where anyone could find it. One row per affected controller is also the
     * more useful record: each organizer can see that their copy of this data
     * subject was erased on this date and why.
     *
     * <p><b>The honest gap.</b> An account that never bought anything and never
     * joined an audience touches no org, so it produces no audit row at all —
     * there is no org to attribute one to and inventing a synthetic one would be
     * fabricating a record. That case is reported as {@code tombstoned=false}
     * and logged at WARN, so it is visible rather than silent. The application
     * log is then the only trace, which is the correct outcome for an erasure
     * that removed no organizer-held personal data in the first place.
     *
     * @return whether at least one durable tombstone row was written
     */
    private boolean writeTombstone(UUID accountId, Set<UUID> orgs, int addresses, int membershipsErased) {
        if (orgs.isEmpty()) {
            log.warn("[buyer] account {} erased with no organizer-held data — no audit_logs "
                    + "tombstone is possible (org_id is NOT NULL); addresses={}", accountId, addresses);
            return false;
        }
        String summary = "Buyer account erased (Art.17) — addresses=" + addresses
                + " memberships=" + membershipsErased + " orgs=" + orgs.size();
        for (UUID orgId : orgs) {
            auditLogger.record(new AuthPrincipal(null, orgId, UserRole.MEMBER, null),
                    AuditActions.BUYER_ACCOUNT_ERASED, "buyer_account", accountId, summary);
        }
        return true;
    }
}
