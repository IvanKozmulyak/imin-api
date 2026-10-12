package com.imin.iminapi.service.retention;

import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Personal data that no reader needs and no retention rule kept.
 *
 * <p>{@code order_recovery_attempts} holds a buyer's address in the clear beside
 * a hashed IP. It is a rate-limit counter with a one-hour window, so a row
 * older than that is inert. Its sibling {@code buyer_verification_attempts}
 * has been swept at 24 hours since it shipped; this mirrors it.
 *
 * <p>Runs daily, ShedLock-serialized across replicas, at a time offset from
 * the other daily sweepers — same shape as {@code ProcessedWebhookEventSweeper}.
 */
@Component
public class PersonalDataRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(PersonalDataRetentionSweeper.class);

    /** Matches {@code imin.buyer.verification-attempt-retention-hours}, the sibling counter's window. */
    private static final Duration RECOVERY_ATTEMPT_RETENTION = Duration.ofHours(24);

    private final OrderRecoveryAttemptRepository recoveryAttempts;

    public PersonalDataRetentionSweeper(OrderRecoveryAttemptRepository recoveryAttempts) {
        this.recoveryAttempts = recoveryAttempts;
    }

    @Scheduled(cron = "0 31 4 * * *")
    @SchedulerLock(name = "PersonalDataRetentionSweeper.recoveryAttempts",
            lockAtLeastFor = "PT1M", lockAtMostFor = "PT15M")
    @Transactional
    public void sweepOrderRecoveryAttempts() {
        Instant cutoff = Instant.now().minus(RECOVERY_ATTEMPT_RETENTION);
        int deleted = recoveryAttempts.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("[retention] removed {} order-recovery attempt row(s) older than 24h (cutoff={})",
                    deleted, cutoff);
        }
    }
}
