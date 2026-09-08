package com.imin.iminapi.service.ai;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.AiGenerationUsage;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AiGenerationUsageRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * poster-17: {@code checkAndRecord} was a plain count-then-insert with no transaction, no lock and
 * no DB-level uniqueness, so concurrent requests from the same user all read {@code used = limit-1},
 * all passed the guard and all inserted — the rolling-24h ceiling could be exceeded by the request
 * concurrency. Deliberately a real multi-threaded test against the real repository: a permissive
 * test double would certify the very gap being closed.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AiQuotaConcurrencyTest {

    @Autowired AiQuotaService quota;
    @Autowired AiGenerationUsageRepository usageRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AiQuotaProperties props;

    @Test
    void concurrentAttempts_cannotPushTheUserPastTheDailyLimit() throws Exception {
        Organization org = new Organization();
        org.setName("Quota Race Org");
        org.setSlug("quota-race-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hello@quota-race.example");
        org.setCountry("DE");
        org = orgRepo.save(org);

        User user = new User();
        user.setEmail("quota-race-" + UUID.randomUUID() + "@example.com");
        user.setOrgId(org.getId());
        user.setRole(UserRole.OWNER);
        user = userRepo.save(user);

        int limit = props.getImagePerDay();
        for (int i = 0; i < limit - 1; i++) {
            AiGenerationUsage seed = new AiGenerationUsage();
            seed.setUserId(user.getId());
            seed.setOrgId(org.getId());
            seed.setKind("image");
            seed.setCreatedAt(Instant.now());
            usageRepo.save(seed);
        }

        // One free slot, six simultaneous callers.
        int threads = 6;
        AuthPrincipal p = new AuthPrincipal(user.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        quota.checkAndRecordImage(p);
                        allowed.incrementAndGet();
                    } catch (Exception ignored) {
                        // 429 AI_QUOTA_EXCEEDED — the expected outcome for all but one caller.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long recorded = usageRepo.countByUserIdAndKindAndCreatedAtAfter(
                user.getId(), "image", Instant.now().minus(Duration.ofDays(2)));
        assertThat(allowed.get()).isEqualTo(1);
        assertThat(recorded).isEqualTo(limit);
    }
}
