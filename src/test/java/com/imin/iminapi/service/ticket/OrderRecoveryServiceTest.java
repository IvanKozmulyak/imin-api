package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderRecoveryServiceTest {

    private OrderRecoveryService build(OrderRepository orders,
                                        EmailService email,
                                        OrderRecoveryAttemptRepository attempts) {
        EmailProperties ep = new EmailProperties();
        ep.setBuyerSiteBaseUrl("https://app.imin.wtf");
        TicketProperties tp = new TicketProperties();
        tp.setSigningSecret("x".repeat(32));
        tp.setRecoveryWindowDays(90);
        tp.setRecoveryMaxPerHour(5);
        return new OrderRecoveryService(orders, email, new EmailTemplateRenderer(),
                ep, tp, attempts);
    }

    @Test
    void sends_email_when_orders_match() {
        OrderRepository orders = mock(OrderRepository.class);
        EmailService email = mock(EmailService.class);
        OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);
        when(attempts.countByEmailAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);
        when(attempts.countByIpHashAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);

        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setToken("ORDTOK");
        o.setEmail("buyer@example.com");
        o.setEventId(UUID.randomUUID());
        o.setCreatedAt(Instant.now());
        when(orders.findRecentForRecovery(eq("buyer@example.com"), isNull(), any()))
                .thenReturn(List.of(o));

        OrderRecoveryService svc = build(orders, email, attempts);
        svc.requestRecovery("Buyer@Example.com ", null, "1.2.3.4");

        verify(email).send(eq("buyer@example.com"), contains("Recover"),
                contains("/order/ORDTOK"), anyString());
        verify(attempts).save(any());
    }

    @Test
    void silent_when_no_matches_but_attempt_is_logged() {
        OrderRepository orders = mock(OrderRepository.class);
        EmailService email = mock(EmailService.class);
        OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);
        when(attempts.countByEmailAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);
        when(attempts.countByIpHashAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);
        when(orders.findRecentForRecovery(anyString(), any(), any())).thenReturn(List.of());

        OrderRecoveryService svc = build(orders, email, attempts);
        svc.requestRecovery("nobody@example.com", null, "1.2.3.4");

        verify(email, never()).send(any(), any(), any(), any());
        verify(attempts).save(any()); // logged regardless
    }

    @Test
    void rate_limited_by_email_skips_send_but_logs_attempt() {
        OrderRepository orders = mock(OrderRepository.class);
        EmailService email = mock(EmailService.class);
        OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);
        when(attempts.countByEmailAndAttemptedAtAfter(eq("buyer@example.com"), any())).thenReturn(6L);
        when(attempts.countByIpHashAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);

        OrderRecoveryService svc = build(orders, email, attempts);
        svc.requestRecovery("buyer@example.com", null, "1.2.3.4");

        verify(orders, never()).findRecentForRecovery(any(), any(), any());
        verify(email, never()).send(any(), any(), any(), any());
        verify(attempts).save(any()); // still logged
    }

    @Test
    void empty_or_malformed_email_logs_attempt_and_returns() {
        OrderRepository orders = mock(OrderRepository.class);
        EmailService email = mock(EmailService.class);
        OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);

        OrderRecoveryService svc = build(orders, email, attempts);
        svc.requestRecovery("not-an-email", null, "1.2.3.4");
        svc.requestRecovery("", null, "1.2.3.4");
        svc.requestRecovery(null, null, "1.2.3.4");

        verify(orders, never()).findRecentForRecovery(any(), any(), any());
        verify(email, never()).send(any(), any(), any(), any());
    }
}
