package com.imin.iminapi.service.retention;

import com.imin.iminapi.marketing.repository.ProviderEventRepository;
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
 * <p>Two tables, found by the same 2026-09 legal audit finding, sharing one
 * shape: they accumulate identifiers about people indefinitely, nothing queries
 * the old rows, and no erasure path reached either.
 *
 * <ul>
 *   <li>{@code order_recovery_attempts} — a buyer's address in the clear beside
 *       a hashed IP. It is a rate-limit counter with a one-hour window, so a row
 *       older than that is inert. Its sibling {@code buyer_verification_attempts}
 *       has been swept at 24 hours since it shipped; this mirrors it.</li>
 *   <li>{@code provider_events.payload} — the raw Resend and Bird webhook
 *       bodies, i.e. recipient addresses and originating phone numbers. Nothing
 *       has ever read the column (the complaint-rate breaker counts rows, it does
 *       not open them), and {@code ProviderEventDedupService} no longer writes
 *       it. This clears what is already on disk. The rows themselves stay — each
 *       one is an idempotency claim, and deleting it would let a provider replay
 *       re-project.</li>
 * </ul>
 *
 * <p>Both run daily, ShedLock-serialized across replicas, at times offset from
 * the other daily sweepers — same shape as {@code ProcessedWebhookEventSweeper}.
 */
@Component
public class PersonalDataRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(PersonalDataRetentionSweeper.class);

    /** Matches {@code imin.buyer.verification-attempt-retention-hours}, the sibling counter's window. */
    private static final Duration RECOVERY_ATTEMPT_RETENTION = Duration.ofHours(24);

    private final OrderRecoveryAttemptRepository recoveryAttempts;
    private final ProviderEventRepository providerEvents;

    public PersonalDataRetentionSweeper(OrderRecoveryAttemptRepository recoveryAttempts,
                                        ProviderEventRepository providerEvents) {
        this.recoveryAttempts = recoveryAttempts;
        this.providerEvents = providerEvents;
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

    @Scheduled(cron = "0 41 4 * * *")
    @SchedulerLock(name = "PersonalDataRetentionSweeper.webhookBodies",
            lockAtLeastFor = "PT1M", lockAtMostFor = "PT15M")
    @Transactional
    public void clearRetainedWebhookBodies() {
        int cleared = providerEvents.clearRetainedPayloads();
        if (cleared > 0) {
            log.info("[retention] cleared {} retained provider-webhook body/bodies", cleared);
        }
    }
}
