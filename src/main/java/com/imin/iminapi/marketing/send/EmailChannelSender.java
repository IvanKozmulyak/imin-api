package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignFailureReason;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.service.CampaignEnded;
import com.imin.iminapi.marketing.render.CampaignEmailAssembler;
import com.imin.iminapi.marketing.render.CampaignEmailRenderer;
import com.imin.iminapi.marketing.render.EmailShellCopy;
import com.imin.iminapi.marketing.render.MergeTags;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.service.SendPathGuard;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.MarketingGuardProperties;
import com.imin.iminapi.marketing.stock.StockAlertSendGuard;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.ai.provenance.AiEmailDisclosure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Spec §2.5 step 3: per-row email worker over campaign_recipients, one batch per call in two transactions. The claim
 * picks the batch (a keyed batch whose outcome is still unknown first, else up to 100 unkeyed pending rows, diverting
 * those the gate no longer passes) and commits its Idempotency-Key on the rows. The delivery re-locks those rows,
 * renders, calls the provider with the stored key and records the outcome. A timeout is replayed once at once with the
 * same key; a restart before the outcome commits leaves the key on the rows, so the next drive replays it. Resend
 * answers a replay with the first result for 24 h, so a key is retired after 23 h and its rows are never sent again.
 */
@Service
public class EmailChannelSender {

    private static final Logger log = LoggerFactory.getLogger(EmailChannelSender.class);
    static final int BATCH_SIZE = 100;
    /** Rolling window the per-org daily cap is measured over — matches CampaignDispatcher. */
    private static final long DAILY_CAP_WINDOW_HOURS = 24;
    /** Skip reason of a member the send gate no longer returns at all: an accepted erasure request. */
    static final String SKIP_ERASE_PENDING = "erase_pending";
    /** Resend replays a key for 24 h; a batch whose outcome is still unknown after this is never sent again. */
    static final Duration KEY_TTL = Duration.ofHours(23);
    /** Wait before the one inline replay of a batch whose call failed on transport. */
    static final Duration REPLAY_DELAY = Duration.ofSeconds(2);

    private final CampaignRecipientRepository recipients;
    private final CampaignRepository campaigns;
    private final CampaignEmailAssembler assembler;
    private final CampaignEmailProvider provider;
    private final UnsubscribeTokenService tokens;
    private final MarketingEmailProperties props;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final ConsumerRepository consumers;
    private final SendGateService sendGate;
    private final MarketingGuardProperties guardProps;
    private final SendPathGuard sendPathGuard;
    private final AddressSourceLines addressSources;
    private final AudiencePlanAccess audiencePlanAccess;
    private final ApplicationEventPublisher eventPublisher;
    /** Stock alerts: the frozen facts every recipient's email quotes. */
    private final StockAlertSendGuard stockGuard;
    /** REQUIRES_NEW: the claim commits the batch key before the provider is called. */
    private final TransactionTemplate claimTx;
    /** REQUIRES_NEW: the delivery holds the batch's rows from the call until the outcome commits. */
    private final TransactionTemplate deliverTx;

    public EmailChannelSender(CampaignRecipientRepository recipients, CampaignRepository campaigns,
                              CampaignEmailAssembler assembler, CampaignEmailProvider provider,
                              UnsubscribeTokenService tokens, MarketingEmailProperties props,
                              OrganizationRepository organizations,
                              MembershipRepository memberships, ConsumerRepository consumers,
                              SendGateService sendGate, MarketingGuardProperties guardProps,
                              SendPathGuard sendPathGuard, AddressSourceLines addressSources,
                              AudiencePlanAccess audiencePlanAccess,
                              ApplicationEventPublisher eventPublisher, PlatformTransactionManager txManager,
                              StockAlertSendGuard stockGuard) {
        this.recipients = recipients;
        this.campaigns = campaigns;
        this.assembler = assembler;
        this.provider = provider;
        this.tokens = tokens;
        this.props = props;
        this.organizations = organizations;
        this.memberships = memberships;
        this.consumers = consumers;
        this.sendGate = sendGate;
        this.guardProps = guardProps;
        this.sendPathGuard = sendPathGuard;
        this.addressSources = addressSources;
        this.audiencePlanAccess = audiencePlanAccess;
        this.eventPublisher = eventPublisher;
        this.stockGuard = stockGuard;
        this.claimTx = new TransactionTemplate(txManager);
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.deliverTx = new TransactionTemplate(txManager);
        this.deliverTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Sends one batch. Returns true if there are (likely) more claimable rows to process.
     *
     * <p>Not transactional itself: the claim and the delivery each commit on their own (REQUIRES_NEW). The key must be
     * durable before the provider call, and the record of what left must be durable before the next batch is claimed;
     * one transaction around both lost the key on a crash and re-sent the batch under a new request.
     */
    public boolean sendNextBatch(Campaign c) {
        Claim claim = claimTx.execute(st -> claim(c));
        if (claim == null || claim.batchKey() == null) return claim != null && claim.more();
        Boolean more = deliverTx.execute(st -> deliver(c, claim));
        return Boolean.TRUE.equals(more);
    }

    /** What the claim decided: stop ({@code batchKey} null, {@code more} false), skip ahead, or deliver this key. */
    private record Claim(String batchKey, boolean resumed, Organization org, boolean more) {
        static Claim stop() { return new Claim(null, false, null, false); }
        static Claim next(boolean more) { return new Claim(null, false, null, more); }
    }

    /** Phase 1: the per-batch gates, then a keyed batch to replay or a fresh batch stamped with a new key. */
    private Claim claim(Campaign c) {
        // Re-read per batch: a campaign stopped by any writer (cancel, fail) sends nothing more, and the drive sees why.
        // ponytail: a plain read, so a stop landing while this batch is at the provider still lets this one batch go.
        String stored = campaigns.findStatusById(c.getId()).orElse(null);
        if (!"sending".equals(stored)) {
            log.warn("[email-sender] campaign {} is '{}' in the database — stopping the drive", c.getId(), stored);
            c.setStatus(stored);
            return Claim.stop();
        }
        // Per-org daily cap (spec §7), re-checked PER BATCH. Checking it only at claim time
        // capped which campaigns start, not how much they send: a single 200k campaign
        // admitted under a 10,000/day cap then drained all 200k. Stopping here leaves the
        // campaign 'sending' with rows queued, so it resumes when the window rolls forward.
        if (recipients.countRecentSendsForOrg(c.getOrgId(),
                Instant.now().minus(DAILY_CAP_WINDOW_HOURS, ChronoUnit.HOURS))
                >= guardProps.getDailyCap()) {
            log.info("[email-sender] org {} at daily cap — pausing campaign {}",
                    c.getOrgId(), c.getId());
            return Claim.stop();
        }
        Organization org = organization(c.getOrgId());
        // Mail that needs the legal footer fails the campaign (rows stay pending) rather than loop.
        if (audiencePlanAccess.legalIdentityRequired(c.getOrigin()) && (org == null || !org.hasLegalIdentity())) {
            failForMissingLegalIdentity(c);
            return Claim.stop();
        }
        // A breaker pause or a moderation hold placed mid-drive stops it before this batch; it stays 'sending' and the
        // dispatcher's claim, which reads the same predicate, resumes it once lifted.
        if (!campaigns.isSendableNow(c.getId(), audiencePlanAccess.sendsEnabled(),
                audiencePlanAccess.legalIdentityAllCampaigns())) {
            log.warn("[email-sender] campaign {} held: org paused, moderation hold or sends switched off — stopping",
                    c.getId());
            return Claim.stop();
        }
        Instant now = Instant.now();
        int expired = recipients.expireUnknownKeys(c.getId(), now.minus(KEY_TTL), now);
        if (expired > 0) {
            log.error("[email-sender] campaign {}: {} rows left a batch whose outcome stayed unknown past {} — "
                    + "skipped as delivery_unconfirmed, never re-sent", c.getId(), expired, KEY_TTL);
        }
        List<String> keyed = recipients.findOldestKeyedGroup(c.getId(), now, now.minus(KEY_TTL));
        if (!keyed.isEmpty()) {
            log.info("[email-sender] campaign {}: replaying batch {} with its stored key", c.getId(), keyed.get(0));
            return new Claim(keyed.get(0), true, org, true);
        }
        List<CampaignRecipient> batch = new ArrayList<>(recipients.claimPendingBatch(c.getId(), BATCH_SIZE, now));
        if (batch.isEmpty()) return Claim.stop();
        // Idempotence belt-and-braces: only rows still 'pending' may be sent. The claim
        // already filters on it, but a row that changed underneath us must never be
        // re-emailed just because it was in the claimed page.
        batch.removeIf(r -> !"pending".equals(r.getStatus()));
        // Unreachable while the claim filters on status; stop the drive rather than spin
        // if it ever is reached — the dispatcher re-claims the campaign either way.
        if (batch.isEmpty()) return Claim.stop();
        // The gate snapshot was taken at materialisation, and a large campaign drains for
        // minutes-to-hours after that. Re-run it over THIS batch so someone who unsubscribed,
        // complained or was deliverability-suppressed in the meantime is diverted rather than
        // emailed — SendGateService is "THE ONLY path that yields sendable recipients".
        Map<UUID, String> diverted = diversions(c, batch);
        applyDiversions(c, batch, diverted);
        batch.removeIf(r -> diverted.containsKey(r.getId()));
        if (batch.isEmpty()) {
            return Claim.next(recipients.countByCampaignIdAndStatus(c.getId(), "pending") > 0);
        }
        String key = "campaign:" + c.getId() + ":" + UUID.randomUUID();
        int stamped = recipients.stampSendKey(batch.stream().map(CampaignRecipient::getId).toList(), key, now);
        if (stamped != batch.size()) {
            // The claim holds these rows locked, so this means a writer the lock does not cover; send nothing.
            throw new IllegalStateException("stamped " + stamped + " of " + batch.size() + " rows for " + key);
        }
        return new Claim(key, false, org, true);
    }

    /** Phase 2+3: lock the keyed rows, render, call the provider with the key, and record the outcome. */
    private boolean deliver(Campaign c, Claim claim) {
        String key = claim.batchKey();
        List<CampaignRecipient> group = new ArrayList<>(recipients.lockKeyedGroup(c.getId(), key));
        long held = recipients.countPendingByKey(c.getId(), key);
        if (held == 0) return recipients.countByCampaignIdAndStatus(c.getId(), "pending") > 0;
        if (group.size() < held) {
            // Another drive holds part of this batch; it records the outcome, and replaying under the key is safe anyway.
            log.warn("[email-sender] campaign {}: batch {} is held elsewhere — stopping this drive", c.getId(), key);
            return false;
        }
        Organization org = claim.org();
        // A replayed batch re-runs the gate in memory only: those rows leave the request, but are recorded as skipped
        // only once the outcome is known, because the first request may already have mailed them. A fresh batch was
        // gated at its claim; only an address erased since (DSAR) is dropped, never sent to a null `to`.
        Map<UUID, String> diverted = new HashMap<>();
        if (claim.resumed()) diverted.putAll(diversions(c, group));
        else divertMissingAddress(group, diverted);
        List<CampaignRecipient> batch = new ArrayList<>(group);
        batch.removeIf(r -> diverted.containsKey(r.getId()));
        if (batch.isEmpty()) {
            applyDiversions(c, group, diverted);
            recipients.clearSendKey(ids(group));
            return recipients.countByCampaignIdAndStatus(c.getId(), "pending") > 0;
        }

        // Template, org brand name, and event poster are constant for the whole campaign —
        // resolve them ONCE per batch, not per recipient. Only the unsubscribe URL varies.
        CampaignEmailAssembler.Context ctx = assembler.contextFor(c, org);
        if (SendPathGuard.STOCK_ALERT_ORIGIN.equals(c.getOrigin())) ctx = ctx.withStockTokens(stockGuard.frozenTokens(c));
        String replyTo = props.replyTo(org);
        // Per-recipient {{firstName}} — resolve display names for the whole batch in two
        // batched queries (membership -> consumer -> display_name), not per row.
        Map<UUID, String> firstNameByMembership = resolveFirstNames(c.getOrgId(), batch);
        Map<UUID, String> sourceByMembership = addressSources.forFirstEmail(c.getOrgId(), batch.stream()
                .map(CampaignRecipient::getMembershipId).filter(Objects::nonNull).distinct().toList());

        AiEmailDisclosure ai = c.aiDisclosure();
        String locale = c.getLocale();
        List<CampaignEmailProvider.OutgoingEmail> outgoing = new ArrayList<>(batch.size());
        for (CampaignRecipient r : batch) {
            String unsubToken = tokens.sign(c.getOrgId(), r.getMembershipId(), c.getId(), "email");
            // Footer opens the buyer page; the List-Unsubscribe header (RFC 8058 one-tap) posts to the API.
            String unsubUrl = props.unsubscribeUrl(unsubToken);
            String unsubPageUrl = props.unsubscribePageUrl(unsubToken);
            String firstName = r.getMembershipId() == null ? null : firstNameByMembership.get(r.getMembershipId());
            CampaignEmailAssembler.Email rendered = assembler.render(ctx,
                    c.getSubject(), c.getPreheader(), c.getBodyMd(), firstName, unsubPageUrl,
                    addressSource(r.getMembershipId() == null ? null : sourceByMembership.get(r.getMembershipId()), locale));
            outgoing.add(new CampaignEmailProvider.OutgoingEmail(
                    ctx.fromHeader(), r.getEmail(), rendered.subject(),
                    rendered.html(), rendered.text(), unsubUrl, ai, replyTo, c.getId()));
        }

        boolean replayed = claim.resumed();
        CampaignEmailProvider.BatchResult result;
        try {
            try {
                result = provider.sendBatch(key, outgoing);
            } catch (CampaignEmailProvider.RetryableBatchFailure ex) {
                if (!ex.transport()) throw ex;
                // A timeout may follow an accepted request; replaying at once with the same key settles it either way.
                replayed = true;
                pause(REPLAY_DELAY);
                log.warn("[email-sender] campaign {}: batch {} failed on transport — replaying once with its key",
                        c.getId(), key);
                result = provider.sendBatch(key, outgoing);
            }
        } catch (CampaignEmailProvider.ReplayMismatch ex) {
            // Resend saw this key with another body, so a request with it landed, but which rows it mailed is unknown:
            // none is counted as sent and none is mailed again.
            log.warn("[email-sender] campaign {}: batch {} was seen with another body — {} rows delivery_unconfirmed",
                    c.getId(), key, group.size());
            recordUnconfirmed(c, group);
            recipients.clearSendKey(ids(group));
            campaigns.touch(c.getId(), Instant.now());
            return recipients.countByCampaignIdAndStatus(c.getId(), "pending") > 0;
        } catch (CampaignEmailProvider.CampaignRejected ex) {
            // A refused key or domain refuses every batch alike: fail the campaign after this one call. Its rows stay
            // pending with their attempts, so Retry continues once IMIN fixes it. A replay keeps its key: the first
            // request with it may have landed before the refusal.
            log.error("[email-sender] campaign {}: provider refused the batch with HTTP {} — failing the campaign, "
                    + "{} rows stay queued", c.getId(), ex.upstreamStatus(), group.size());
            if (!replayed) recipients.clearSendKey(ids(group));
            failForProviderRejection(c);
            return false;
        } catch (ApiException ex) {
            log.warn("[email-sender] batch {} failed for campaign {} — leaving {} rows pending under their key: {}",
                    key, c.getId(), group.size(), ex.getMessage());
            Instant now = Instant.now();
            long retryAfter = ex instanceof CampaignEmailProvider.RetryableBatchFailure rf && rf.retryAfter() != null
                    ? rf.retryAfter().getSeconds() : 0L;
            for (CampaignRecipient r : group) {
                short attempt = (short) (r.getAttemptCount() + 1);
                r.setAttemptCount(attempt);
                r.setLastEventAt(now);
                r.setNextAttemptAt(now.plusSeconds(Math.max(backoffSeconds(attempt), retryAfter)));
                recipients.save(r);
            }
            campaigns.touch(c.getId(), now);
            // Bail out of the drive instead of looping straight back into a provider that just
            // refused us. The key stays on the rows, so the dispatcher's stale-`sending` reclaim
            // replays this exact request once the backoff has elapsed.
            return false;
        }

        recordOutcome(c, batch, result);
        applyDiversions(c, group, diverted);
        recipients.clearSendKey(ids(group));

        // Heartbeat: bump campaigns.updated_at so the dispatcher's stale-`sending` reclaim
        // (status='sending' AND updated_at < now()-5min) does not fire mid-send. A no-op
        // self-assign would NOT dirty the entity, so campaigns.save(c) could skip the UPDATE
        // and never touch updated_at. Use an explicit @Modifying UPDATE that always flushes.
        campaigns.touch(c.getId(), Instant.now());

        return recipients.countByCampaignIdAndStatus(c.getId(), "pending") > 0;
    }

    /**
     * Sent rows with their own ids; a row the provider refused alone fails with provider_rejected; a row the answer does
     * not account for is skipped/delivery_unconfirmed, neither counted as sent nor mailed again.
     */
    private void recordOutcome(Campaign c, List<CampaignRecipient> batch, CampaignEmailProvider.BatchResult result) {
        CampaignEmailProvider.BatchResult r = result == null ? CampaignEmailProvider.BatchResult.accepted(List.of()) : result;
        int untracked = 0;
        List<CampaignRecipient> unconfirmed = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < batch.size(); i++) {
            CampaignRecipient row = batch.get(i);
            if (r.unconfirmed(i)) {
                unconfirmed.add(row);
                continue;
            }
            row.setAttemptCount((short) (row.getAttemptCount() + 1));
            row.setLastEventAt(now);
            if (r.rejected(i)) {
                row.setStatus("failed");
                row.setErrorCode(CampaignRecipient.ERROR_PROVIDER_REJECTED);
                log.warn("[email-sender] campaign {} recipient {} refused by the provider: {}",
                        c.getId(), row.getId(), r.rowErrors().get(i));
            } else {
                row.setStatus("sent");
                row.setProviderMessageId(r.idAt(i));
                row.setSentAt(now);
                // Without an id no delivery/bounce/complaint event resolves to this row by id; say so on the row.
                if (r.idAt(i) == null) {
                    row.setErrorCode(CampaignRecipient.ERROR_NO_PROVIDER_ID);
                    untracked++;
                }
            }
            recipients.save(row);
        }
        if (untracked > 0) {
            log.warn("[email-sender] campaign {}: {} of {} sent rows came back without a provider id",
                    c.getId(), untracked, batch.size());
        }
        if (!unconfirmed.isEmpty()) {
            log.error("[email-sender] campaign {}: the provider's answer does not account for {} of {} rows — "
                    + "delivery_unconfirmed", c.getId(), unconfirmed.size(), batch.size());
            recordUnconfirmed(c, unconfirmed);
        }
    }

    /** Rows whose outcome is unknown for good: skipped/delivery_unconfirmed, no sent_at, never sent again. */
    private void recordUnconfirmed(Campaign c, List<CampaignRecipient> rows) {
        Instant now = Instant.now();
        for (CampaignRecipient row : rows) {
            row.setStatus("skipped");
            row.setSkipReason(CampaignRecipient.SKIP_DELIVERY_UNCONFIRMED);
            row.setNextAttemptAt(null);
            row.setLastEventAt(now);
            recipients.save(row);
        }
        log.info("[email-sender] campaign {}: {} rows skipped as delivery_unconfirmed", c.getId(), rows.size());
    }

    private static List<UUID> ids(List<CampaignRecipient> rows) {
        return rows.stream().map(CampaignRecipient::getId).toList();
    }

    private static void pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Attempt-based delay before a failed row may be claimed again. Deliberately coarser than
     * the dispatcher's 30s tick and no shorter than its 5-minute stale-`sending` reclaim from
     * attempt 2 on, so a sustained outage backs off instead of hammering.
     */
    private static long backoffSeconds(short attempt) {
        return switch (attempt) {
            case 0, 1 -> 60L;
            case 2 -> 300L;
            default -> 900L;
        };
    }

    /**
     * Why each row of the batch must not be mailed now, by row id: the Send Gate's own exclusion reason, a send-path
     * guard skip, or {@code no_email}, first match wins. Reads only.
     */
    private Map<UUID, String> diversions(Campaign c, List<CampaignRecipient> batch) {
        Map<UUID, String> out = new HashMap<>();
        divertNoLongerSendable(c, batch, out);
        divertGuarded(c, batch, out);
        divertMissingAddress(batch, out);
        return out;
    }

    /** Records the decided rows of {@code rows} as skipped with their reason. */
    private void applyDiversions(Campaign c, List<CampaignRecipient> rows, Map<UUID, String> diverted) {
        if (diverted.isEmpty()) return;
        Instant now = Instant.now();
        for (CampaignRecipient r : rows) {
            String reason = diverted.get(r.getId());
            if (reason == null) continue;
            r.setStatus("skipped");
            r.setSkipReason(reason);
            r.setLastEventAt(now);
            recipients.save(r);
            log.info("[email-sender] campaign {} recipient {} not sendable now ({}) — skipping",
                    c.getId(), r.getId(), reason);
        }
    }

    /**
     * Re-runs the Send Gate for a claimed batch: every row that no longer passes gets the gate's own exclusion reason.
     * A member the gate drops without a reason (erase_pending) gets {@code erase_pending}.
     * Read-only and tenant-scoped (SendGateService.evaluate), four batched queries per call.
     *
     * <p>Rows with a null membership_id (DSAR-erased) cannot be gated and are left alone —
     * they carry no consent state to re-check.
     */
    private void divertNoLongerSendable(Campaign c, List<CampaignRecipient> batch, Map<UUID, String> out) {
        List<UUID> membershipIds = batch.stream()
                .map(CampaignRecipient::getMembershipId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (membershipIds.isEmpty()) return;
        SendGateService.GateResult gate = sendGate.evaluate(c.getOrgId(), membershipIds);
        Map<UUID, String> reasonByMembership = new HashMap<>();
        for (ExclusionReason ex : gate.excluded()) {
            reasonByMembership.put(ex.membershipId(), ex.reason());
        }
        // Neither sendable nor excluded: the gate dropped it silently, so it must not be mailed either.
        Set<UUID> sendable = new HashSet<>(gate.sendable());
        for (UUID mid : membershipIds) {
            if (!sendable.contains(mid)) reasonByMembership.putIfAbsent(mid, SKIP_ERASE_PENDING);
        }
        for (CampaignRecipient r : batch) {
            String reason = r.getMembershipId() == null ? null : reasonByMembership.get(r.getMembershipId());
            if (reason != null) out.putIfAbsent(r.getId(), reason);
        }
    }

    /**
     * Re-runs SendPathGuard over the rows the gate kept: a holdout written, a cap reached by another campaign, or
     * consent lost since materialisation gives the row that reason.
     */
    private void divertGuarded(Campaign c, List<CampaignRecipient> batch, Map<UUID, String> out) {
        List<UUID> membershipIds = batch.stream()
                .filter(r -> !out.containsKey(r.getId()))
                .map(CampaignRecipient::getMembershipId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (membershipIds.isEmpty()) return;
        Map<UUID, String> reasonByMembership = sendPathGuard.skipReasons(c, membershipIds, Instant.now());
        if (reasonByMembership.isEmpty()) return;
        for (CampaignRecipient r : batch) {
            String reason = r.getMembershipId() == null ? null : reasonByMembership.get(r.getMembershipId());
            if (reason != null) out.putIfAbsent(r.getId(), reason);
        }
    }

    /**
     * Every row with no address gets {@code skipped}/{@code no_email}. A row loses its address exactly once: DSAR
     * erasure nulls {@code campaign_recipients.email} for an erased membership
     * ({@code CampaignRecipientRepository.redactPiiByMembershipId}), which can catch a row that
     * is still queued. The Send Gate cannot cover this — its {@code no_email} clause reads the
     * consumer's address, and the erased membership it would need is already gone — so the check
     * belongs here, on the row that is about to be sent. Same reason value the gate uses, so the
     * recipient log's skip chips stay one vocabulary.
     */
    private static void divertMissingAddress(List<CampaignRecipient> batch, Map<UUID, String> out) {
        for (CampaignRecipient r : batch) {
            if (r.getEmail() == null || r.getEmail().isBlank()) out.putIfAbsent(r.getId(), "no_email");
        }
    }

    /** Stays failed until the organizer retries once IMIN fixed the key or domain; rows keep their place in the queue. */
    private void failForProviderRejection(Campaign c) {
        if (campaigns.markFailedIfActive(c.getId(), CampaignFailureReason.PROVIDER_REJECTED, Instant.now()) != 1) {
            log.warn("[email-sender] campaign {} no longer scheduled or sending — not failing it", c.getId());
            c.setStatus(campaigns.findStatusById(c.getId()).orElse(null));
            return;
        }
        c.setStatus("failed");
        c.setLastError(CampaignFailureReason.PROVIDER_REJECTED);
        eventPublisher.publishEvent(CampaignEnded.failed(c.getId(), c.getOrgId(),
                CampaignFailureReason.PROVIDER_REJECTED));
    }

    /** Stays failed until the organizer retries; the dispatcher never claims a failed campaign. */
    private void failForMissingLegalIdentity(Campaign c) {
        String error = ErrorCode.ORG_LEGAL_IDENTITY_MISSING.name();
        // Conditional, like markFailed: a campaign canceled meanwhile keeps that status.
        if (campaigns.markFailedIfActive(c.getId(), error, Instant.now()) != 1) {
            log.warn("[email-sender] campaign {} no longer scheduled or sending — not failing it", c.getId());
            c.setStatus(campaigns.findStatusById(c.getId()).orElse(null));   // the drive stops on what is stored
            return;
        }
        c.setStatus("failed");
        c.setLastError(error);
        log.warn("[email-sender] campaign {} failed: org {} has no legal name and contact", c.getId(), c.getOrgId());
        eventPublisher.publishEvent(CampaignEnded.failed(c.getId(), c.getOrgId(),
                CampaignFailureReason.LEGAL_IDENTITY_MISSING));
    }

    /**
     * The campaign's org, for the header name, From and footer. Failure-isolated: a lookup hiccup
     * must not fail a live send; it falls back to the configured From and no identity line.
     */
    private Organization organization(UUID orgId) {
        try {
            return organizations.findById(orgId).orElse(null);
        } catch (Exception e) {
            log.debug("[email-sender] org lookup failed for {}: {}", orgId, e.getMessage());
            return null;
        }
    }

    /**
     * Batch-resolve first names for a claimed recipient batch: membership_id ->
     * greetable first name (from consumers.display_name), falling back to the neutral
     * greeting when a member has no usable name. Two batched queries, no per-row IO.
     */
    private Map<UUID, String> resolveFirstNames(UUID orgId, List<CampaignRecipient> batch) {
        List<UUID> membershipIds = batch.stream()
                .map(CampaignRecipient::getMembershipId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (membershipIds.isEmpty()) return Map.of();
        List<Membership> mems = memberships.findByIdsAndOrgId(membershipIds, orgId);
        List<UUID> consumerIds = mems.stream().map(Membership::getConsumerId).distinct().toList();
        Map<UUID, String> nameByConsumer = new HashMap<>();
        if (!consumerIds.isEmpty()) {
            for (Consumer cn : consumers.findAllByConsumerIdIn(consumerIds)) {
                nameByConsumer.put(cn.getConsumerId(), MergeTags.firstName(cn.getDisplayName()));
            }
        }
        Map<UUID, String> out = new HashMap<>();
        for (Membership m : mems) {
            out.put(m.getMembershipId(), nameByConsumer.get(m.getConsumerId()));
        }
        return out;
    }

    /** Art. 14 notice for an imported member's first email; the privacy page lives on the buyer site. */
    private CampaignEmailRenderer.AddressSource addressSource(String platforms, String locale) {
        if (platforms == null) return null;
        String base = props.getBuyerSiteBaseUrl() == null ? "" : props.getBuyerSiteBaseUrl().replaceAll("/+$", "");
        return new CampaignEmailRenderer.AddressSource(platforms,
                base.isEmpty() ? null : base + "/legal/privacy" + EmailShellCopy.forLocale(locale).privacyUrlSuffix());
    }
}
