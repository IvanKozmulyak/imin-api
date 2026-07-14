package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.MarketingGuardProperties;
import com.imin.iminapi.marketing.service.QuietHours;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spec §2.5 step 1+4: the DB-as-queue dispatcher. Every 30s it claims due email campaigns
 * (scheduled+due, retryable-failed, or stale-sending) and delegates each to the injected
 * CampaignSendUnit (a separate bean so its @Transactional boundary engages). The dispatcher
 * itself is non-transactional — the per-campaign transaction lives in CampaignSendUnit.
 *
 * <p>Gating (spec §2.5 step 4, §7): an org's campaigns are skipped entirely while the org
 * is complaint-paused ({@code organizations.marketing_paused_at} set) or inside its local
 * email quiet-hours window (22:00–09:00 org-local). Per-member frequency capping and the
 * per-org daily cap are enforced downstream in the send path, not here.
 */
@Component
public class CampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatcher.class);
    private static final long STALE_MINUTES = 5;
    private static final long DAILY_CAP_WINDOW_HOURS = 24;

    private final CampaignRepository campaigns;
    private final CampaignSendUnit sendUnit;
    private final QuietHours quietHours;
    private final OrganizationRepository orgs;
    private final CampaignRecipientRepository recipients;
    private final MarketingGuardProperties guardProps;

    public CampaignDispatcher(CampaignRepository campaigns, CampaignSendUnit sendUnit,
                              QuietHours quietHours, OrganizationRepository orgs,
                              CampaignRecipientRepository recipients,
                              MarketingGuardProperties guardProps) {
        this.campaigns = campaigns;
        this.sendUnit = sendUnit;
        this.quietHours = quietHours;
        this.orgs = orgs;
        this.recipients = recipients;
        this.guardProps = guardProps;
    }

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "campaign_dispatcher", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    public void run() {
        runOnce();
    }

    /** Non-scheduled body so tests can drive one pass deterministically. */
    public void runOnce() {
        Instant now = Instant.now();
        for (Campaign c : claimDue(now)) {
            try {
                sendUnit.processOne(c);   // crosses the proxy → @Transactional engages
            } catch (Exception e) {
                log.error("[dispatcher] campaign {} processOne threw: {}", c.getId(), e.getMessage());
                sendUnit.markFailed(c, e.getMessage());
            }
        }
    }

    /**
     * The campaign ids eligible to send at {@code now}: due-scheduled, retryable-failed
     * (attempts&lt;3), and stale-`sending` reclaim — MINUS orgs that are complaint-paused
     * or inside email quiet hours (spec §2.5 step 4, §7). Separated from the scheduled
     * tick so it is unit-testable.
     */
    public List<UUID> claimDueCampaignIds(Instant now) {
        return claimDue(now).stream().map(Campaign::getId).toList();
    }

    /**
     * Broad SQL claim (scheduled-due / retryable-failed / stale-sending, SKIP LOCKED)
     * filtered in Java to drop complaint-paused and quiet-hours orgs. Returns the loaded
     * campaigns so the tick can process them without a second round-trip.
     */
    private List<Campaign> claimDue(Instant now) {
        Instant staleBefore = now.minus(STALE_MINUTES, ChronoUnit.MINUTES);
        List<Campaign> due = campaigns.claimDue(now, staleBefore);
        List<Campaign> eligible = new ArrayList<>(due.size());
        Map<UUID, Organization> orgCache = new HashMap<>();
        Instant capWindowStart = now.minus(DAILY_CAP_WINDOW_HOURS, ChronoUnit.HOURS);
        for (Campaign c : due) {
            Organization o = orgCache.computeIfAbsent(c.getOrgId(),
                    id -> orgs.findById(id).orElse(null));
            if (o == null) continue;                                         // org gone → skip
            if (o.getMarketingPausedAt() != null) continue;                  // complaint breaker
            if (quietHours.isEmailQuiet(o.getTimezone(), now)) continue;     // quiet hours
            // Per-org daily cap (spec §7): once the org's rolling-24h send count reaches the
            // configured cap, hold its campaigns until the window rolls forward.
            if (recipients.countRecentSendsForOrg(c.getOrgId(), capWindowStart) >= guardProps.getDailyCap()) {
                log.info("[dispatcher] org {} at daily cap — holding campaign {}", c.getOrgId(), c.getId());
                continue;
            }
            eligible.add(c);
        }
        return eligible;
    }
}
