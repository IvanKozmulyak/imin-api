package com.imin.iminapi.service.retention;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.OrderRecoveryAttempt;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code order_recovery_attempts} stores a buyer's address in the clear beside a
 * hashed IP and had no purge at all, while its sibling
 * {@code buyer_verification_attempts} has been swept at 24 hours since it
 * shipped. The rows are a rate-limit counter with a one-hour window — nothing
 * reads one older than that.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class OrderRecoveryAttemptRetentionTest {

    @Autowired OrderRecoveryAttemptRepository attempts;
    @Autowired PersonalDataRetentionSweeper sweeper;

    @Test
    void the_sweep_drops_attempts_past_the_window_and_keeps_the_recent_ones() {
        String stale = "stale-" + System.nanoTime() + "@example.com";
        String fresh = "fresh-" + System.nanoTime() + "@example.com";
        save(stale, Instant.now().minus(Duration.ofHours(48)));
        save(fresh, Instant.now().minus(Duration.ofMinutes(5)));

        sweeper.sweepOrderRecoveryAttempts();

        assertThat(attempts.countByEmailAndAttemptedAtAfter(stale, Instant.EPOCH)).isZero();
        assertThat(attempts.countByEmailAndAttemptedAtAfter(fresh, Instant.EPOCH)).isEqualTo(1);
    }

    private void save(String email, Instant at) {
        OrderRecoveryAttempt a = new OrderRecoveryAttempt();
        a.setEmail(email);
        a.setIpHash("0".repeat(64));
        a.setAttemptedAt(at);
        attempts.save(a);
    }
}
