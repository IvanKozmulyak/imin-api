package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
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
 * CampaignSendUnit (a separate bean so its @Transactional boundary engages). The dispatcher's
 * only transaction is the claim (lock, filter, flip to sending); the per-campaign transactions live in CampaignSendUnit.
 *
 * <p>Gating (spec §2.5 step 4, §7): paused or missing orgs are filtered in the claim SQL, quiet hours and the daily cap in Java.
 */
@Component
public class CampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatcher.class);
    private static final long STALE_MINUTES = 5;
    private static final long DAILY_CAP_WINDOW_HOURS = 24;
    /** Campaigns one run claims at most. */
    static final int CLAIM_LIMIT = 10;
    // ponytail: more than SCAN_LIMIT quiet-hours or capped campaigns ahead in the queue still delay the ones behind them.
    static final int SCAN_LIMIT = 100;
    // ponytail: two minutes under lockAtMostFor; a single batch that outlasts the margin can still overlap the next run.
    static final Duration RUN_BUDGET = Duration.ofMinutes(8);

    private final CampaignRepository campaigns;
    private final CampaignSendUnit sendUnit;
    private final QuietHours quietHours;
    private final OrganizationRepository orgs;
    private final CampaignRecipientRepository recipients;
    private final MarketingGuardProperties guardProps;
    private final AudiencePlanAccess audiencePlanAccess;
    /** REQUIRES_NEW: the claim commits its flip before any drive starts, whatever the caller's transaction. */
    private final TransactionTemplate claimTx;
    /** Reads the run deadline only; claims and heartbeats stay on wall time. */
    private final Clock clock;

    public CampaignDispatcher(CampaignRepository campaigns, CampaignSendUnit sendUnit,
                              QuietHours quietHours, OrganizationRepository orgs,
                              CampaignRecipientRepository recipients,
                              MarketingGuardProperties guardProps,
                              AudiencePlanAccess audiencePlanAccess,
                              PlatformTransactionManager txManager, Clock clock) {
        this.campaigns = campaigns;
        this.sendUnit = sendUnit;
        this.quietHours = quietHours;
        this.orgs = orgs;
        this.recipients = recipients;
        this.guardProps = guardProps;
        this.audiencePlanAccess = audiencePlanAccess;
        this.clock = clock;
        this.claimTx = new TransactionTemplate(txManager);
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "campaign_dispatcher", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    public void run() {
        runOnce();
    }

    /** Non-scheduled body so tests can drive one pass deterministically. */
    public void runOnce() {
        runOnce(clock.instant().plus(RUN_BUDGET));
    }

    /** One pass that starts no batch and no campaign after {@code deadline}, so the run ends under its lock. */
    void runOnce(Instant deadline) {
        List<Claim> claimed = claim(Instant.now());
        for (int i = 0; i < claimed.size(); i++) {
            if (!clock.instant().isBefore(deadline)) {
                release(claimed.subList(i, claimed.size()));
                return;
            }
            Campaign c = claimed.get(i).campaign();
            try {
                sendUnit.processOne(c, deadline);   // crosses the proxy → @Transactional engages
            } catch (Exception e) {
                log.error("[dispatcher] campaign {} processOne threw: {}", c.getId(), e.getMessage());
                try {
                    sendUnit.markFailed(c, e.getMessage());
                } catch (RuntimeException markFailure) {
                    // Left 'sending'; the stale reclaim picks it up. The rest of the claim still runs.
                    log.error("[dispatcher] campaign {} could not be marked failed: {}", c.getId(), markFailure.getMessage());
                }
            }
        }
    }

    /** A claimed campaign with the status and heartbeat it held before the claim, and the claim's own stamp. */
    private record Claim(Campaign campaign, String priorStatus, Instant priorUpdatedAt, Instant claimedAt) {}

    /**
     * Claim and flip in one transaction: the SKIP LOCKED row locks last until the flip to 'sending' (with a fresh
     * heartbeat) commits, so no other run can claim the same campaign. Campaigns the Java filters drop are never written.
     */
    private List<Claim> claim(Instant now) {
        // Postgres keeps microseconds; the release compares this stamp for equality.
        Instant claimedAt = now.truncatedTo(ChronoUnit.MICROS);
        List<Claim> claimed = claimTx.execute(st -> {
            List<Claim> out = new ArrayList<>();
            for (Campaign c : eligible(now)) out.add(new Claim(c, c.getStatus(), c.getUpdatedAt(), claimedAt));
            if (!out.isEmpty()) campaigns.markClaimed(out.stream().map(cl -> cl.campaign().getId()).toList(), claimedAt);
            return out;
        });
        for (Claim cl : claimed) {
            cl.campaign().setStatus("sending");
            cl.campaign().setUpdatedAt(claimedAt);
        }
        return claimed;
    }

    /** Out of run budget: campaigns the run claimed but never started go back to their prior state. */
    private void release(List<Claim> unstarted) {
        for (Claim cl : unstarted) {
            try {
                campaigns.releaseClaim(cl.campaign().getId(), cl.priorStatus(), cl.priorUpdatedAt(), cl.claimedAt());
            } catch (RuntimeException e) {
                // Left 'sending' with the claim's heartbeat; the stale reclaim resumes it.
                log.error("[dispatcher] campaign {} claim not released: {}", cl.campaign().getId(), e.getMessage());
            }
        }
        log.info("[dispatcher] run budget spent — released {} unstarted campaigns", unstarted.size());
    }

    /**
     * The campaign ids eligible to send at {@code now}: due-scheduled, retryable-failed
     * (attempts&lt;3), and stale-`sending` reclaim — MINUS orgs that are gone, complaint-paused,
     * inside email quiet hours or at their daily cap (spec §2.5 step 4, §7). Separated from the scheduled
     * tick so it is unit-testable. Read-only: outside a transaction its row locks end with the statement.
     */
    public List<UUID> claimDueCampaignIds(Instant now) {
        return eligible(now).stream().map(Campaign::getId).toList();
    }

    /**
     * SQL claim (scheduled-due / retryable-failed / stale-sending of live, unpaused orgs, SKIP LOCKED) of up to
     * SCAN_LIMIT rows, filtered in Java for quiet hours and the daily cap, then cut to CLAIM_LIMIT, so held
     * campaigns ahead in the queue do not take every slot. Inside the claim transaction the locks hold until commit.
     */
    private List<Campaign> eligible(Instant now) {
        Instant staleBefore = now.minus(STALE_MINUTES, ChronoUnit.MINUTES);
        // Audience-plan holds, missing orgs and complaint pauses are filtered in SQL, so they never use up the scan.
        List<Campaign> due = campaigns.claimDue(now, staleBefore, audiencePlanAccess.sendsEnabled(),
                audiencePlanAccess.legalIdentityAllCampaigns(), SCAN_LIMIT);
        List<Campaign> eligible = new ArrayList<>(CLAIM_LIMIT);
        Map<UUID, Boolean> orgHeld = new HashMap<>();
        Instant capWindowStart = now.minus(DAILY_CAP_WINDOW_HOURS, ChronoUnit.HOURS);
        for (Campaign c : due) {
            if (eligible.size() == CLAIM_LIMIT) break;
            if (orgHeld.computeIfAbsent(c.getOrgId(), id -> isHeld(id, now, capWindowStart))) continue;
            eligible.add(c);
        }
        return eligible;
    }

    /** Quiet hours (22:00–09:00 org-local) or the per-org rolling-24h daily cap (spec §7). */
    private boolean isHeld(UUID orgId, Instant now, Instant capWindowStart) {
        Organization o = orgs.findById(orgId).orElse(null);
        if (o == null) return true;                                          // deleted since the claim query
        if (quietHours.isEmailQuiet(o.getTimezone(), now)) return true;
        if (recipients.countRecentSendsForOrg(orgId, capWindowStart) >= guardProps.getDailyCap()) {
            log.info("[dispatcher] org {} at daily cap — holding its campaigns", orgId);
            return true;
        }
        return false;
    }
}
