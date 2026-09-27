package com.imin.iminapi.service.ticket;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/v1/public/orders/recover} is unauthenticated (SecurityConfig
 * permitAll) and has no rate-limit bucket — its only cap is an in-DB 5/hour
 * counter. So an attacker chooses how often the Resend round trip happens, and
 * every one of those sends used to run inside {@code requestRecovery}'s
 * transaction: a pooled JDBC connection (prod max 20) held open for the whole
 * outbound HTTP call. {@code BuyerOrderActionsController} already treats exactly
 * this pattern as a defect for exactly this emailer — "holding a pooled
 * connection open across an outbound HTTP request is how a slow third party
 * turns into an exhausted connection pool".
 *
 * <p>Mocks are stubbed in {@code @TestBean} factories: stubbing in the test raced the async
 * fan-feature recompute calling {@code OrderRepository}, which could steal the stub.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class OrderRecoveryTransactionBoundaryTest {

    private static final String BUYER = "buyer@example.com";

    record SendCall(String to, boolean inTransaction) {}

    private static final Queue<SendCall> SENDS = new ConcurrentLinkedQueue<>();

    @Autowired OrderRecoveryService service;
    @TestBean OrderRepository orders;
    @TestBean EmailService email;

    static OrderRepository orders() {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setToken("ORDTOK");
        o.setEmail(BUYER);
        o.setEventId(UUID.randomUUID());
        o.setCreatedAt(Instant.now());
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findRecentForRecovery(eq(BUYER), isNull(), any())).thenReturn(List.of(o));
        return repo;
    }

    static EmailService email() {
        EmailService mail = mock(EmailService.class);
        doAnswer(inv -> {
            SENDS.add(new SendCall(inv.getArgument(0),
                    TransactionSynchronizationManager.isActualTransactionActive()));
            return null;
        }).when(mail).send(anyString(), anyString(), anyString(), anyString());
        return mail;
    }

    @Test
    void the_resend_call_does_not_hold_a_pooled_connection() {
        service.requestRecovery(BUYER, null, "1.2.3.4");

        List<SendCall> toBuyer = SENDS.stream().filter(c -> BUYER.equals(c.to())).toList();
        assertThat(toBuyer).as("the recovery email is sent exactly once").hasSize(1);
        assertThat(toBuyer.get(0).inTransaction())
                .as("the outbound Resend send must happen outside any transaction")
                .isFalse();
    }
}
