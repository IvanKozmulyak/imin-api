package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.email.BuyerMailEvents;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two properties that make the buyer account mail safe to send at all.
 *
 * <p>Every one of these sends used to happen inline in the {@code @Transactional}
 * service method that caused it: a Resend round trip with no configured timeout,
 * made while the flow still held its pooled connection — on the highest-volume
 * unauthenticated endpoints in the API — and made before the work it describes
 * was committed.
 */
@IminIntegrationTest
class BuyerMailListenerTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager txManager;
    @Autowired @Qualifier("ticketEmailExecutor") Executor mailExecutor;
    @Autowired RecordingEmailService mail;

    /** A slow Resend must not be able to hold a database connection open. */
    @Test
    void the_send_happens_with_no_transaction_in_scope() {
        String to = "ada-" + UUID.randomUUID() + "@example.test";

        new TransactionTemplate(txManager).executeWithoutResult(status ->
                publisher.publishEvent(new BuyerMailEvents.AccountExistsNotice(to, "en")));

        BuyerMailSync.drain(mailExecutor);
        int index = indexOfOnlyMailTo(to);
        assertThat(mail.sentInTransaction(index))
                .as("the listener must run after the commit, outside the transaction")
                .isFalse();
        assertThat(mail.sentOnThread(index))
                .as("and off the request thread, so a slow send costs a mail thread")
                .isNotSameAs(Thread.currentThread());
    }

    /** Mail about work that was rolled back describes something that never happened. */
    @Test
    void a_rolled_back_transaction_mails_nothing() {
        String to = "ada-" + UUID.randomUUID() + "@example.test";
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                publisher.publishEvent(new BuyerMailEvents.VerificationCode(to, "en", "123456", 15));
                throw new IllegalStateException("signup blew up after the code was issued");
            });
        } catch (IllegalStateException expected) {
            // the point of the test
        }

        assertThat(BuyerMailSync.sentTo(mail, mailExecutor, to)).isEmpty();
    }

    /**
     * A transaction that fails at commit, after the code was issued, mails nothing either:
     * the send waits for AFTER_COMMIT, not for the commit to start.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"verification code", "account-exists notice"})
    void a_transaction_that_fails_while_committing_mails_nothing(String mailKind) {
        String to = "ada-" + UUID.randomUUID() + "@example.test";
        Object event = mailKind.equals("verification code")
                ? new BuyerMailEvents.VerificationCode(to, "en", "123456", 15)
                : new BuyerMailEvents.AccountExistsNotice(to, "en");
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                publisher.publishEvent(event);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("commit blew up after the code was issued");
                    }
                });
            });
        } catch (IllegalStateException expected) {
            // the point of the test
        }

        assertThat(BuyerMailSync.sentTo(mail, mailExecutor, to)).isEmpty();
    }

    /** Position of the one mail to {@code to} in the recorder, for its per-send facts. */
    private int indexOfOnlyMailTo(String to) {
        List<RecordingEmailService.SentEmail> sent = mail.sent();
        List<Integer> hits = java.util.stream.IntStream.range(0, sent.size())
                .filter(i -> to.equalsIgnoreCase(sent.get(i).to())).boxed().toList();
        assertThat(hits).as("the mail was sent").hasSize(1);
        return hits.get(0);
    }
}
