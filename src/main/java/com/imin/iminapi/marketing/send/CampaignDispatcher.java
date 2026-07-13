package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Spec §2.5 step 1: the DB-as-queue dispatcher. Every 30s it claims due email campaigns
 * (scheduled+due, retryable-failed, or stale-sending) and delegates each to the injected
 * CampaignSendUnit (a separate bean so its @Transactional boundary engages). The dispatcher
 * itself is non-transactional — the per-campaign transaction lives in CampaignSendUnit.
 */
@Component
public class CampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatcher.class);
    private static final long STALE_MINUTES = 5;

    private final CampaignRepository campaigns;
    private final CampaignSendUnit sendUnit;

    public CampaignDispatcher(CampaignRepository campaigns, CampaignSendUnit sendUnit) {
        this.campaigns = campaigns;
        this.sendUnit = sendUnit;
    }

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "campaign_dispatcher", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    public void run() {
        runOnce();
    }

    /** Non-scheduled body so tests can drive one pass deterministically. */
    public void runOnce() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(STALE_MINUTES, ChronoUnit.MINUTES);
        List<Campaign> due = campaigns.claimDue(now, staleBefore);
        for (Campaign c : due) {
            try {
                sendUnit.processOne(c);   // crosses the proxy → @Transactional engages
            } catch (Exception e) {
                log.error("[dispatcher] campaign {} processOne threw: {}", c.getId(), e.getMessage());
                sendUnit.markFailed(c, e.getMessage());
            }
        }
    }
}
