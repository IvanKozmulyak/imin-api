package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.ErasedAddress;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.util.LogSafe;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final ErasedAddressRepository erasedAddressRepo;

    public AudienceBackfillJob(OrderRepository orderRepo,
                               AudienceOrderProjector projector,
                               ErasedAddressRepository erasedAddressRepo) {
        this.orderRepo = orderRepo;
        this.projector = projector;
        this.erasedAddressRepo = erasedAddressRepo;
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

        // The erasure ledger (V99), loaded once. Orders are retained under the
        // invoicing exemption, so every erased person is still in `pairs` — without
        // this filter the job re-creates the Consumer + Membership that
        // AudienceErasureJob deleted an hour earlier, and Art.17 erasure becomes a
        // pause rather than a deletion. A NEW purchase after erasure is new data and
        // is projected by AudienceOrderProjector on the live event path; only this
        // replay-from-history path is filtered.
        Set<String> erasedPlatformWide = new HashSet<>();
        Set<String> erasedPerOrg = new HashSet<>();
        for (ErasedAddress e : erasedAddressRepo.findAllEntries()) {
            if (e.getOrgId() == null) erasedPlatformWide.add(e.getEmailNormalized());
            else erasedPerOrg.add(e.getOrgId() + "|" + e.getEmailNormalized());
        }

        int processed = 0;
        int skippedErased = 0;
        for (Object[] pair : pairs) {
            java.util.UUID orgId = (java.util.UUID) pair[0];
            String email = (String) pair[1];
            String normalizedEmail = EmailNormalizer.normalize(email);
            if (erasedPlatformWide.contains(normalizedEmail)
                    || erasedPerOrg.contains(orgId + "|" + normalizedEmail)) {
                skippedErased++;
                continue;
            }
            try {
                projector.upsertMembership(orgId, normalizedEmail, email);
                processed++;
            } catch (Exception e) {
                log.error("Backfill failed for org={} email={}: {}", orgId, LogSafe.email(normalizedEmail),
                        LogSafe.redact(e.getMessage()));
            }
        }
        log.info("AudienceBackfillJob: done — {} memberships processed, {} skipped (erased)",
                processed, skippedErased);
    }
}
