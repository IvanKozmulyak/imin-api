package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Per-campaign send unit (spec §2.5). A dedicated bean so its @Transactional boundary
 * engages via the Spring proxy when called from CampaignDispatcher — self-invocation
 * on the dispatcher would make @Transactional inert. processOne flips status→sending,
 * materializes recipients, drives the per-row sender to drain, then flips status→sent
 * WITHIN one transaction, so a mid-drive crash rolls the status flip back (the campaign
 * stays reclaimable) rather than leaving a half-committed `sent` with pending rows.
 */
@Component
public class CampaignSendUnit {

    private static final Logger log = LoggerFactory.getLogger(CampaignSendUnit.class);

    private final CampaignRepository campaigns;
    private final RecipientMaterializer materializer;
    private final EmailChannelSender emailSender;

    public CampaignSendUnit(CampaignRepository campaigns, RecipientMaterializer materializer,
                            EmailChannelSender emailSender) {
        this.campaigns = campaigns;
        this.materializer = materializer;
        this.emailSender = emailSender;
    }

    @Transactional
    public void processOne(Campaign c) {
        if (!"sending".equals(c.getStatus())) {
            c.setStatus("sending");
            campaigns.save(c);
        }
        materializer.materialize(c);
        // Drive batches until nothing pending remains. Bounded loop; each call heartbeats.
        int guard = 0;
        while (emailSender.sendNextBatch(c) && guard++ < 10_000) {
            // keep sending
        }
        c.setStatus("sent");
        c.setSentAt(Instant.now());
        campaigns.save(c);
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
