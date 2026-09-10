package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.email.BuyerMailEvents;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The two properties that make the buyer account mail safe to send at all.
 *
 * <p>Every one of these sends used to happen inline in the {@code @Transactional}
 * service method that caused it: a Resend round trip with no configured timeout,
 * made while the flow still held its pooled connection — on the highest-volume
 * unauthenticated endpoints in the API — and made before the work it describes
 * was committed.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class BuyerMailListenerTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager txManager;
    @Autowired @Qualifier("ticketEmailExecutor") Executor mailExecutor;

    @MockitoBean EmailService email;

    /** A slow Resend must not be able to hold a database connection open. */
    @Test
    void the_send_happens_with_no_transaction_in_scope() {
        AtomicReference<Boolean> txActive = new AtomicReference<>(null);
        AtomicBoolean sameThread = new AtomicBoolean(true);
        Thread caller = Thread.currentThread();
        doAnswer(invocation -> {
            txActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            sameThread.set(Thread.currentThread() == caller);
            return null;
        }).when(email).send(anyString(), anyString(), anyString(), anyString());

        new TransactionTemplate(txManager).executeWithoutResult(status ->
                publisher.publishEvent(new BuyerMailEvents.AccountExistsNotice(
                        "ada@example.com", "en")));

        verify(email, timeout(10_000)).send(anyString(), anyString(), anyString(), anyString());
        assertThat(txActive.get())
                .as("the listener must run after the commit, outside the transaction")
                .isFalse();
        assertThat(sameThread.get())
                .as("and off the request thread, so a slow send costs a mail thread")
                .isFalse();
    }

    /** Mail about work that was rolled back describes something that never happened. */
    @Test
    void a_rolled_back_transaction_mails_nothing() {
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                publisher.publishEvent(new BuyerMailEvents.VerificationCode(
                        "ada@example.com", "en", "123456", 15));
                throw new IllegalStateException("signup blew up after the code was issued");
            });
        } catch (IllegalStateException expected) {
            // the point of the test
        }

        BuyerMailSync.drain(mailExecutor);
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }
}
