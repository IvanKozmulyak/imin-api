package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.predictor.service.PredictorMarketingEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Per-campaign send unit (spec §2.5). A dedicated bean so its transaction boundaries
 * engage via the Spring proxy when called from CampaignDispatcher — self-invocation on
 * the dispatcher would make @Transactional inert.
 *
 * <p><b>processOne is deliberately NOT transactional.</b> Emails leave irreversibly one
 * batch at a time, so the record of what left has to be durable at the same granularity:
 * the status flip, the materialisation and every batch commit in their own transaction
 * (materialize / sendNextBatch are REQUIRES_NEW; the campaign status flips run through
 * {@link #newTx}). A pod restart or a statement timeout mid-drive therefore leaves the
 * already-sent rows recorded as sent — the dispatcher re-claims the campaign, the
 * materializer no-ops because rows exist, and only the still-pending rows are sent.
 * Wrapping the whole drive in one transaction (the shape this class had) rolled back
 * every 'sent' flip and every materialised row, so the automatic re-claim re-sent the
 * whole audience — up to three times, and without any ceiling on the scheduled arm.
 */
@Component
public class CampaignSendUnit {

    private static final Logger log = LoggerFactory.getLogger(CampaignSendUnit.class);

    /** Attempt budget per recipient row — mirrors claimPendingBatch's `attempt_count < 3`. */
    static final short MAX_RECIPIENT_ATTEMPTS = 3;
    /** Every status a row can hold once its email actually left. */
    private static final List<String> LEFT_THE_BUILDING = List.of(
            "sent", "delivered", "opened", "clicked", "bounced", "complained", "unsubscribed");

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final RecipientMaterializer materializer;
    private final EmailChannelSender emailSender;
    private final ApplicationEventPublisher eventPublisher;
    private final AudiencePlanAccess audiencePlanAccess;
    /** REQUIRES_NEW template: the campaign status flips commit on their own, like the batches. */
    private final TransactionTemplate newTx;
    /** Reads the run deadline only. */
    private final Clock clock;

    public CampaignSendUnit(CampaignRepository campaigns, CampaignRecipientRepository recipients,
                            RecipientMaterializer materializer,
                            EmailChannelSender emailSender, ApplicationEventPublisher eventPublisher,
                            PlatformTransactionManager txManager, AudiencePlanAccess audiencePlanAccess,
                            Clock clock) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.materializer = materializer;
        this.emailSender = emailSender;
        this.eventPublisher = eventPublisher;
        this.audiencePlanAccess = audiencePlanAccess;
        this.clock = clock;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void processOne(Campaign c) {
        processOne(c, Instant.MAX);
    }

    /** Drives the campaign, starting no batch after {@code deadline}; the dispatcher passes its run budget. */
    public void processOne(Campaign c, Instant deadline) {
        if (!"sending".equals(c.getStatus())) {
            Instant now = Instant.now();
            Integer flipped = newTx.execute(st -> campaigns.markSendingIf(c.getId(), c.getStatus(), now));
            if (flipped == null || flipped == 0) {
                log.warn("[send-unit] campaign {} is no longer '{}' — not driving it", c.getId(), c.getStatus());
                return;
            }
            c.setStatus("sending");
            c.setUpdatedAt(now);
        }
        // ponytail: materialize runs whole, outside the run budget; set-based, ~0.6 s per 5,000 members, but the gate
        // binds the whole audience in one IN list, so past 65,535 members the bind limit fails it.
        materializer.materialize(c);
        // Drive batches until nothing claimable remains. Bounded loop; each call commits
        // its own batch and heartbeats.
        int guard = 0;
        while (sendsAllowed(c) && emailSender.sendNextBatch(c) && guard++ < 10_000) {
            if (!clock.instant().isBefore(deadline)) {
                // Run budget spent: stays 'sending' with rows queued; the stale reclaim resumes it, like the daily-cap stop.
                log.info("[send-unit] campaign {} paused: dispatcher run budget spent", c.getId());
                return;
            }
        }
        // The sender failed it mid-drive (legal identity removed) or found it stopped (canceled); finish() must not overwrite that.
        if (!"sending".equals(c.getStatus())) return;
        if (!sendsAllowed(c)) {
            // Same as the paused path: stays 'sending' with its queue intact; the claim resumes it once re-enabled.
            log.warn("[send-unit] campaign {} held mid-send: audience plan sends are disabled", c.getId());
            return;
        }
        finish(c);
    }

    /** Re-read per batch so switching audience-plan sends off stops a drive already in progress. */
    private boolean sendsAllowed(Campaign c) {
        return audiencePlanAccess.sendsEnabled() || !AudiencePlanAccess.CAMPAIGN_ORIGIN.equals(c.getOrigin());
    }

    /**
     * Terminal state for a drained campaign (mkt-core-2). Rows that burned their attempt
     * budget are retired to 'failed' with an error_code first, so the outcome is read off
     * real row state. A campaign where NOTHING left and something died is 'failed', not
     * 'sent': stamping it 'sent' reported 0 sent against a non-zero recipientCount and made
     * POST /retry (which requires 'failed') refuse the only campaign that needed it.
     */
    private void finish(Campaign c) {
        newTx.executeWithoutResult(st -> recipients.failExhaustedPending(
                c.getId(), MAX_RECIPIENT_ATTEMPTS, "send_failed", Instant.now()));
        // Rows still inside their attempt budget mean the drive stopped early — the per-org
        // daily cap bit, or a provider failure put the batch into backoff. Leave the campaign
        // 'sending' with a stale heartbeat so the dispatcher's stale-sending reclaim resumes
        // it; stamping it 'sent' here would strand every remaining recipient for good.
        long queued = recipients.countRetryablePending(c.getId(), MAX_RECIPIENT_ATTEMPTS);
        if (queued > 0) {
            log.info("[send-unit] campaign {} paused with {} recipients still queued", c.getId(), queued);
            return;
        }
        long left = recipients.countByCampaignIdAndStatusIn(c.getId(), LEFT_THE_BUILDING);
        long dead = recipients.countByCampaignIdAndStatus(c.getId(), "failed");
        if (left == 0 && dead > 0) {
            newTx.executeWithoutResult(st -> markFailed(c, "no recipients could be sent"));
            return;
        }
        Instant sentAt = Instant.now();
        Integer marked = newTx.execute(st -> campaigns.markSentIfSending(c.getId(), sentAt));
        if (marked == null || marked == 0) {
            log.warn("[send-unit] campaign {} left 'sending' before it drained — not marked sent", c.getId());
            return;
        }
        c.setStatus("sent");
        c.setSentAt(sentAt);
        // Predictor trigger (task §4): a completed send may have moved sales — re-forecast.
        // AFTER_COMMIT + debounced in ReforecastTriggerService, so it never rides this send tx.
        if (c.getEventId() != null) {
            eventPublisher.publishEvent(new PredictorMarketingEvents.CampaignSent(c.getEventId()));
        }
    }

    @Transactional
    public void markFailed(Campaign c, String error) {
        String lastError = error == null ? "send failed" : error.substring(0, Math.min(500, error.length()));
        // Conditional: a campaign canceled or sent meanwhile keeps that status.
        if (campaigns.markFailedIfActive(c.getId(), lastError, Instant.now()) == 0) {
            log.warn("[send-unit] campaign {} not marked failed: no longer scheduled or sending ({})", c.getId(), error);
            return;
        }
        c.setStatus("failed");
        log.error("[send-unit] campaign {} failed (attempt {}): {}", c.getId(),
                campaigns.findAttemptsById(c.getId()).orElse(null), error);
    }
}
