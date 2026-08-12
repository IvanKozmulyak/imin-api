package com.imin.iminapi.buyer.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduling and cancelling account deletion — the synchronous half of §7.1.
 *
 * <p><b>"Scheduled" is not "deferred".</b> Three things happen the instant the
 * buyer confirms, and only the destructive cascade waits out the 30 days:
 *
 * <ol>
 *   <li>{@code status = 'delete_pending'}, {@code delete_at = now + 30d} — the
 *       same window {@code DsarService.requestErase} uses, so a buyer and an
 *       organizer asking for the same erasure get the same grace period.</li>
 *   <li>Every session is revoked and the cookie is cleared by the controller.
 *       Deletion is a credential-level event: it belongs on the same list as a
 *       password change (§2.2).</li>
 *   <li><b>Every membership is unsubscribed on every channel.</b> Art.21
 *       objection is synchronous and takes effect on receipt, not after a grace
 *       period — a buyer who asked to be deleted must not receive a campaign on
 *       day 12. This is the part that would be silently wrong if deletion were
 *       implemented as "set a flag and let the job sort it out".</li>
 * </ol>
 *
 * <p><b>Cancelling is never implicit.</b> {@link #cancel} is only reachable from
 * an explicit "Keep my account" call on an authenticated session. Signing in
 * during the grace window does not cancel anything: someone may sign in on day
 * 12 purely to pull up a ticket, and revoking their erasure because they needed
 * to get through a door would be a decision the product made on their behalf.
 */
@Service
public class BuyerAccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(BuyerAccountDeletionService.class);

    /** Same 30 days as {@code DsarService.requestErase}. */
    public static final int GRACE_DAYS = 30;

    /**
     * {@code consent_records.source} for the unsubscribes below.
     *
     * <p>Not {@code dsar_object}: that source means an organizer actioned an
     * Art.21 request against their own list, and the two are worth telling apart
     * when someone later asks why a membership went quiet. {@code VARCHAR(64)}
     * (V49:12) — comfortably wide enough.
     */
    static final String CONSENT_SOURCE = "buyer_account_deletion";

    private final BuyerAccountRepository accounts;
    private final BuyerAccountEmailRepository emails;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final ConsentService consentService;
    private final BuyerSessionService sessions;
    private final AuditLogger auditLogger;

    public BuyerAccountDeletionService(BuyerAccountRepository accounts,
                                       BuyerAccountEmailRepository emails,
                                       ConsumerRepository consumers,
                                       MembershipRepository memberships,
                                       ConsentService consentService,
                                       BuyerSessionService sessions,
                                       AuditLogger auditLogger) {
        this.accounts = accounts;
        this.emails = emails;
        this.consumers = consumers;
        this.memberships = memberships;
        this.consentService = consentService;
        this.sessions = sessions;
        this.auditLogger = auditLogger;
    }

    /**
     * Schedules erasure and takes immediate effect. Idempotent: a second call on
     * an already-pending account returns the existing {@code delete_at} rather
     * than sliding the deadline forward, because re-confirming a deletion must
     * not quietly buy the account another 30 days.
     *
     * @return the instant the erasure job becomes due
     */
    @Transactional
    public Instant requestDeletion(UUID accountId) {
        BuyerAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Account"));

        if (BuyerAccount.STATUS_DELETE_PENDING.equals(account.getStatus())
                && account.getDeleteAt() != null) {
            // Already scheduled. Still revoke sessions — the caller may be a
            // second device — but leave the deadline where it is.
            sessions.revokeAll(accountId);
            return account.getDeleteAt();
        }

        Instant deleteAt = Times.nowMicros().plus(GRACE_DAYS, ChronoUnit.DAYS);
        account.setStatus(BuyerAccount.STATUS_DELETE_PENDING);
        account.setDeleteAt(deleteAt);
        accounts.save(account);

        int unsubscribed = unsubscribeEverything(accountId);
        int revoked = sessions.revokeAll(accountId);

        log.info("[buyer] deletion scheduled account={} deleteAt={} unsubscribed={} sessionsRevoked={}",
                accountId, deleteAt, unsubscribed, revoked);

        return deleteAt;
    }

    /**
     * "Keep my account" — clears the deletion. Reaching this requires a live
     * session, which requires signing in again, because {@link #requestDeletion}
     * revoked them all.
     *
     * <p>The unsubscribes are deliberately <b>not</b> reversed. Consent is not a
     * side effect of the account's lifecycle: it was withdrawn by an
     * affirmative act and only an affirmative act restores it. Silently
     * re-subscribing someone because they changed their mind about deleting
     * their account is precisely the consent-laundering the epic's
     * {@code marketing_optouts} table exists to prevent (§6.3).
     */
    @Transactional
    public void cancel(UUID accountId) {
        BuyerAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Account"));

        if (!BuyerAccount.STATUS_DELETE_PENDING.equals(account.getStatus())) {
            return; // nothing scheduled — idempotent no-op
        }

        account.setStatus(BuyerAccount.STATUS_ACTIVE);
        account.setDeleteAt(null);
        accounts.save(account);

        log.info("[buyer] deletion cancelled account={}", accountId);
    }

    /**
     * Unsubscribes every membership the account's verified addresses resolve to,
     * on every channel, across every org.
     *
     * <p>Fans out over the address SET, not over one address: {@code consumers}
     * is unique on {@code normalized_email} (V47:10), so an account with N
     * verified addresses is N {@code Consumer} rows with N separate audiences
     * behind them. Unsubscribing "the" consumer would leave the rest subscribed.
     *
     * @return how many (membership, channel) pairs were unsubscribed
     */
    private int unsubscribeEverything(UUID accountId) {
        List<String> verified = emails.findVerifiedEmailsByBuyerAccountId(accountId);
        int count = 0;
        Set<UUID> seen = new HashSet<>();

        for (String address : verified) {
            UUID consumerId = consumers.findByNormalizedEmail(address)
                    .map(c -> c.getConsumerId())
                    .orElse(null);
            if (consumerId == null || !seen.add(consumerId)) continue;

            for (Membership m : memberships.findAllByConsumerId(consumerId)) {
                AuthPrincipal principal = new AuthPrincipal(null, m.getOrgId(), UserRole.MEMBER, null);
                for (String channel : List.of("email", "sms")) {
                    try {
                        // R1.6 seam: pass ConsentOrigin.DATA_SUBJECT once the parameter lands.
                        consentService.unsubscribe(m.getOrgId(), m.getMembershipId(),
                                CONSENT_SOURCE, channel, principal);
                        count++;
                    } catch (RuntimeException e) {
                        // One org's membership failing must not strand the rest of
                        // the objection, nor block the deletion itself.
                        log.error("[buyer] unsubscribe failed account={} membership={} channel={}: {}",
                                accountId, m.getMembershipId(), channel, e.getMessage());
                    }
                }
            }
        }
        return count;
    }

    /**
     * One audit row per org that holds a membership for this account.
     *
     * <p>{@code audit_logs.org_id} is NOT NULL (V21:3) and every reader filters
     * on it, so a buyer-initiated, org-less event has to be recorded against the
     * orgs it actually touched or not at all. When the account touched no org
     * there is no row — see {@code BuyerAccountErasureService} for why that is
     * reported rather than faked.
     */
    void auditPerOrg(Set<UUID> orgIds, String action, UUID accountId, String summary) {
        for (UUID orgId : orgIds) {
            auditLogger.record(new AuthPrincipal(null, orgId, UserRole.MEMBER, null),
                    action, "buyer_account", accountId, summary);
        }
    }

    /** Exposed for the erasure service and for the deletion audit trail. */
    Set<UUID> orgsHoldingAccount(UUID accountId) {
        Set<UUID> orgIds = new HashSet<>();
        for (String address : emails.findVerifiedEmailsByBuyerAccountId(accountId)) {
            consumers.findByNormalizedEmail(address).ifPresent(c ->
                    memberships.findAllByConsumerId(c.getConsumerId())
                            .forEach(m -> orgIds.add(m.getOrgId())));
        }
        return orgIds;
    }

    /** Records the request against every org that holds a copy of this buyer. */
    @Transactional
    public void auditDeletionRequested(UUID accountId, Instant deleteAt) {
        auditPerOrg(orgsHoldingAccount(accountId), AuditActions.BUYER_ACCOUNT_DELETE_REQUESTED,
                accountId, "Buyer requested account deletion — erasure due " + deleteAt);
    }

    /** Records the cancellation against the same set. */
    @Transactional
    public void auditDeletionCancelled(UUID accountId) {
        auditPerOrg(orgsHoldingAccount(accountId), AuditActions.BUYER_ACCOUNT_DELETE_CANCELLED,
                accountId, "Buyer cancelled scheduled account deletion");
    }
}
