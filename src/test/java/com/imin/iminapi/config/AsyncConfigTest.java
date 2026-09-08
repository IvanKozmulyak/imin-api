package com.imin.iminapi.config;

import com.imin.iminapi.service.EventContentService;
import com.imin.iminapi.service.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class AsyncConfigTest {

    @MockitoBean(name = "replicateRestClient")
    RestClient replicateRestClient;

    @MockitoBean EventContentService eventContentService;
    @MockitoBean AuthService authService;

    @Autowired @Qualifier("campaignSendExecutor") Executor campaignSendExecutor;
    @Autowired @Qualifier("ticketEmailExecutor") Executor ticketEmailExecutor;

    @Test
    void campaignSendExecutor_isSeparatePoolFromTicketExecutor() {
        assertThat(campaignSendExecutor).isNotSameAs(ticketEmailExecutor);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) campaignSendExecutor;
        assertThat(pool.getThreadNamePrefix()).isEqualTo("campaign-send-");
    }

    /**
     * {@code TicketIssuanceEmailer.onTicketsIssued} is
     * {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async("ticketEmailExecutor")},
     * so the submit happens inside the afterCommit synchronization — on the Stripe
     * webhook's own thread, after the Order and the {@code processed_webhook_events}
     * dedup row have already committed. With the default {@code AbortPolicy} a full
     * queue threw {@code TaskRejectedException} out of {@code commit()} into the
     * webhook response, and Stripe's retry then short-circuited at the dedup marker:
     * that buyer's ticket email was gone for good. The pool is shared with the refund
     * and milestone mailers, so a burst on either is enough to fill it.
     *
     * <p>The geocoding pool's discard-with-a-log handler is deliberately NOT what this
     * one does — a dropped map pin is recoverable, a dropped ticket is not. Running on
     * the caller delays the webhook response instead of losing the mail.
     */
    @Test
    void ticketEmailExecutor_runs_the_overflow_rather_than_rejecting_it() throws Exception {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) ticketEmailExecutor;
        CountDownLatch release = new CountDownLatch(1);
        int saturate = pool.getMaxPoolSize() + pool.getQueueCapacity();
        AtomicBoolean overflowRan = new AtomicBoolean(false);
        try {
            for (int i = 0; i < saturate; i++) {
                pool.execute(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertThatCode(() -> pool.execute(() -> overflowRan.set(true)))
                    .as("a rejection here propagates out of commit() and loses a paid buyer's ticket email")
                    .doesNotThrowAnyException();
        } finally {
            release.countDown();
        }
        assertThat(overflowRan)
                .as("the overflow task must still run — on the caller — not be dropped")
                .isTrue();
    }
}
