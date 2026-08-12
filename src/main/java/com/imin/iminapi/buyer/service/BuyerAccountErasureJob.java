package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Executes buyer account erasure once the 30-day grace period is up (§7.2).
 *
 * <p>A <b>sibling</b> of {@code AudienceErasureJob}, not an extension of it.
 * The two answer different questions — "which memberships did an organizer ask
 * to erase" versus "which people asked to be erased from the platform" — and
 * the second one fans out across orgs in a way the first must never do.
 *
 * <p>{@code 0 30 2 * * *} is half an hour after {@code AudienceErasureJob}'s
 * {@code 0 0 2 * * *}. The offset is deliberate: both are ShedLock jobs that
 * walk the same membership/consumer graph, and overlapping them would have them
 * contending for the same rows nightly for no benefit. Half an hour is far more
 * than either needs at any plausible volume.
 *
 * <p>Failures are per-account, exactly as in {@code AudienceErasureJob}: one
 * account that cannot be erased must not stop the queue behind it. Each
 * {@code erase} call is its own transaction, so a failure rolls that account
 * back whole rather than half-erasing it.
 */
@Component
public class BuyerAccountErasureJob {

    private static final Logger log = LoggerFactory.getLogger(BuyerAccountErasureJob.class);

    private final BuyerAccountRepository accounts;
    private final BuyerAccountErasureService erasureService;

    public BuyerAccountErasureJob(BuyerAccountRepository accounts,
                                  BuyerAccountErasureService erasureService) {
        this.accounts = accounts;
        this.erasureService = erasureService;
    }

    @Scheduled(cron = "0 30 2 * * *")
    @SchedulerLock(name = "buyer_account_erasure", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        List<BuyerAccount> due = accounts.findErasureDue(Instant.now());
        if (due.isEmpty()) return;

        log.info("[buyer] BuyerAccountErasureJob: {} accounts due for erasure", due.size());
        int erased = 0;
        for (BuyerAccount account : due) {
            try {
                erasureService.erase(account.getId());
                erased++;
            } catch (Exception e) {
                log.error("[buyer] erasure failed for account {}: {}", account.getId(), e.getMessage(), e);
            }
        }
        log.info("[buyer] BuyerAccountErasureJob: erased {}/{}", erased, due.size());
    }
}
