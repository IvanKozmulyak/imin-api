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
