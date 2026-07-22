package com.imin.iminapi.audience.service;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-shot backfill: iterates all orders with a stripe_payment_intent_id (paid orders)
 * through the same upsertMembership path as live ingestion → identical projection.
 * ShedLock-guarded so only one replica runs at a time.
 *
 * <p>The job runs daily at 03:00 UTC; it is idempotent and safe to re-run.
 * For a true one-shot bootstrap, disable the cron after initial run in prod.
 */
@Component
public class AudienceBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(AudienceBackfillJob.class);

    private final OrderRepository orderRepo;
    private final AudienceOrderProjector projector;

    public AudienceBackfillJob(OrderRepository orderRepo, AudienceOrderProjector projector) {
        this.orderRepo = orderRepo;
        this.projector = projector;
    }

    /**
     * Also runs once on startup so a deploy self-heals projection gaps (e.g. orders
     * issued while an event-listener bug was live) without waiting for the nightly
     * cron. Idempotent by design; cheap at current scale.
     * ponytail: unguarded on multi-replica (Railway runs one instance); reuse the
     * ShedLock lock here if replicas ever appear.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            run();
        } catch (Exception e) {
            log.warn("AudienceBackfillJob startup run failed (nightly cron will retry): {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "audience_backfill", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        log.info("AudienceBackfillJob: starting");
        // Fetch all orgs via distinct orgId from orders — then process per buyer email per org
        List<Object[]> pairs = orderRepo.findDistinctOrgAndEmailPairs();
        int processed = 0;
        for (Object[] pair : pairs) {
            java.util.UUID orgId = (java.util.UUID) pair[0];
            String email = (String) pair[1];
            String normalizedEmail = EmailNormalizer.normalize(email);
            try {
                projector.upsertMembership(orgId, normalizedEmail, email);
                processed++;
            } catch (Exception e) {
                log.error("Backfill failed for org={} email={}: {}", orgId, normalizedEmail, e.getMessage());
            }
        }
        log.info("AudienceBackfillJob: done — {} memberships processed", processed);
    }
}
