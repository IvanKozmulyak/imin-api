package com.imin.iminapi.marketing.send;

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
    /** REQUIRES_NEW template: the campaign status flips commit on their own, like the batches. */
    private final TransactionTemplate newTx;

    public CampaignSendUnit(CampaignRepository campaigns, CampaignRecipientRepository recipients,
                            RecipientMaterializer materializer,
                            EmailChannelSender emailSender, ApplicationEventPublisher eventPublisher,
                            PlatformTransactionManager txManager) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.materializer = materializer;
        this.emailSender = emailSender;
        this.eventPublisher = eventPublisher;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void processOne(Campaign c) {
        if (!"sending".equals(c.getStatus())) {
            c.setStatus("sending");
            newTx.executeWithoutResult(st -> campaigns.save(c));
        }
        materializer.materialize(c);
        // Drive batches until nothing claimable remains. Bounded loop; each call commits
        // its own batch and heartbeats.
        int guard = 0;
        while (emailSender.sendNextBatch(c) && guard++ < 10_000) {
            // keep sending
        }
        finish(c);
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
        long left = recipients.countByCampaignIdAndStatusIn(c.getId(), LEFT_THE_BUILDING);
        long dead = recipients.countByCampaignIdAndStatus(c.getId(), "failed");
        if (left == 0 && dead > 0) {
            newTx.executeWithoutResult(st -> markFailed(c, "no recipients could be sent"));
            return;
        }
        newTx.executeWithoutResult(st -> {
            c.setStatus("sent");
            c.setSentAt(Instant.now());
            campaigns.save(c);
        });
        // Predictor trigger (task §4): a completed send may have moved sales — re-forecast.
        // AFTER_COMMIT + debounced in ReforecastTriggerService, so it never rides this send tx.
        if (c.getEventId() != null) {
            eventPublisher.publishEvent(new PredictorMarketingEvents.CampaignSent(c.getEventId()));
        }
    }

    @Transactional
    public void markFailed(Campaign c, String error) {
        Campaign fresh = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElse(c);
        fresh.setStatus("failed");
        fresh.setAttempts((short) (fresh.getAttempts() + 1));
        fresh.setLastError(error == null ? "send failed" : error.substring(0, Math.min(500, error.length())));
        campaigns.save(fresh);
        log.error("[send-unit] campaign {} failed (attempt {}): {}", c.getId(), fresh.getAttempts(), error);
    }
}
