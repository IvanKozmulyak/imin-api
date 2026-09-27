package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.engine.FanFeatureCalculator;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.util.LogSafe;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 3-year rule: email-subscribed members with no contact from the person within the ConsentGate retention window
 * lose their marketing basis (operator unsubscribe, not sticky) and their profiling columns. Orders and tickets stay.
 * Members whose only basis is a pre-provenance bulk import are counted and skipped: their contact age is unknown.
 * With {@code retention-job-enabled=false} it only counts and logs.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    public static final String SOURCE = FanFeatureCalculator.RETENTION_SOURCE;

    /** A feature row older than this may miss a recent purchase, so the member is skipped until it is refreshed. */
    static final Duration FRESHNESS = Duration.ofHours(48);

    /**
     * Counts of one run: past the window, legacy bulk imports skipped (unknown contact age, never unsubscribed),
     * basis removed, and writes that failed (skipped until the next run).
     */
    public record Result(boolean enabled, int orgs, int expired, int importedWithoutProvenance, int cleared,
                         int failed) {}

    private final FanFeatureRepository features;
    private final ConsentGate gate;
    private final ConsentService consentService;
    private final MembershipRepository memberships;
    private final AudiencePlanAccess access;
    private final AudiencePlanProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Calls {@link #run()} through the proxy so its scheduler lock applies. */
    private final ObjectProvider<RetentionJob> self;

    public RetentionJob(FanFeatureRepository features,
                        ConsentGate gate,
                        ConsentService consentService,
                        MembershipRepository memberships,
                        AudiencePlanAccess access,
                        AudiencePlanProperties props,
                        PlatformTransactionManager txManager,
                        Clock clock,
                        ObjectProvider<RetentionJob> self) {
        this.features = features;
        this.gate = gate;
        this.consentService = consentService;
        this.memberships = memberships;
        this.access = access;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.self = self;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Europe/Paris")
    public void scheduled() {
        try {
            self.getObject().run();
        } catch (Exception e) {
            log.error("RetentionJob: run failed (next night retries): {} {}",
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    @SchedulerLock(name = "audience_retention", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public Result run() {
        boolean enabled = Boolean.TRUE.equals(props.getRetentionJobEnabled());
        Instant freshSince = clock.instant().minus(FRESHNESS);
        int orgs = 0;
        int expired = 0;
        int legacy = 0;
        int cleared = 0;
        int failed = 0;
        for (UUID orgId : features.findOrgIdsWithSubscribedMembers()) {
            if (!access.isEnabled(orgId)) continue;
            ConsentGate.RetentionScan scan = gate.retentionScan(orgId, freshSince);
            List<UUID> ids = scan.targets();
            if (ids.isEmpty() && scan.importedWithoutProvenance() == 0) continue;
            orgs++;
            expired += ids.size();
            legacy += scan.importedWithoutProvenance();
            if (!enabled) continue;
            for (UUID membershipId : ids) {
                try {
                    if (Boolean.TRUE.equals(tx.execute(s -> clear(orgId, membershipId, freshSince)))) cleared++;
                } catch (Exception e) {
                    failed++;
                    log.warn("RetentionJob: skipped membership {}: {} {}", membershipId,
                            e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
                }
            }
        }
        Result result = new Result(enabled, orgs, expired, legacy, cleared, failed);
        if (enabled) {
            log.info("RetentionJob: done, {} memberships past the retention window in {} orgs, {} imported without"
                    + " provenance skipped, {} cleared, {} failed", expired, orgs, legacy, cleared, failed);
        } else {
            log.info("RetentionJob: dry run, {} memberships past the retention window in {} orgs, {} imported without"
                    + " provenance skipped, nothing written", expired, orgs, legacy);
        }
        return result;
    }

    /**
     * Membership lock first (the projector's order), then re-check, so a consent that landed after the scan wins.
     * Orders are read directly because the feature projection may not have caught up with a recent purchase.
     */
    private boolean clear(UUID orgId, UUID membershipId, Instant freshSince) {
        Membership m = memberships.lockByIdAndOrgId(membershipId, orgId).orElse(null);
        if (m == null || !"subscribed".equals(m.getConsentStatus())) return false;
        if (!gate.isRetentionExpired(orgId, membershipId, freshSince)) return false;
        if (gate.hasPaidOrderWithinRetention(orgId, membershipId)) return false;
        consentService.unsubscribe(orgId, membershipId, SOURCE, ConsentOrigin.OPERATOR, null);
        features.clearProfiling(membershipId);
        return true;
    }
}
