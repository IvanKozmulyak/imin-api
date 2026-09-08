package com.imin.iminapi.service.ticket;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class OrderRecoveryTransactionBoundaryTest {

    @Autowired OrderRecoveryService service;
    @MockitoBean OrderRepository orders;
    @MockitoBean EmailService email;

    @Test
    void the_resend_call_does_not_hold_a_pooled_connection() {
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        doAnswer(inv -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(email).send(anyString(), anyString(), anyString(), anyString());

        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setToken("ORDTOK");
        o.setEmail("buyer@example.com");
        o.setEventId(UUID.randomUUID());
        o.setCreatedAt(Instant.now());
        when(orders.findRecentForRecovery(eq("buyer@example.com"), isNull(), any()))
                .thenReturn(List.of(o));

        service.requestRecovery("buyer@example.com", null, "1.2.3.4");

        assertThat(inTransaction)
                .as("the outbound Resend send must happen outside any transaction")
                .isFalse();
    }
}
