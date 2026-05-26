package com.imin.iminapi.refund;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.OrderRecoveryAttempt;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundRequestServiceTest {

    OrderRepository orders = mock(OrderRepository.class);
    OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);
    RefundRequestTokenRepository tokens = mock(RefundRequestTokenRepository.class);
    RefundRequestRepository requests = mock(RefundRequestRepository.class);
    EmailService email = mock(EmailService.class);
    EmailTemplateRenderer renderer = mock(EmailTemplateRenderer.class);
    EmailProperties emailProps = new EmailProperties();
    TicketProperties ticketProps = new TicketProperties();
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    RefundRequestService service;

    @BeforeEach
    void setUp() {
        emailProps.setBuyerSiteBaseUrl("https://app.test");
        emailProps.setFromAddress("noreply@test");
        ticketProps.setRecoveryMaxPerHour(5);
        when(renderer.render(anyString(), any()))
            .thenReturn(new EmailTemplateRenderer.Rendered("<html/>", "txt"));
        service = new RefundRequestService(orders, attempts, tokens, requests,
            email, renderer, emailProps, ticketProps, publisher);
    }

    @Test
    void requestLink_no_order_returns_silently_and_records_attempt() {
        when(orders.findRecentForRecovery(eq("nobody@x"), any(), any()))
            .thenReturn(List.of());

        service.requestLink("nobody@x", "1.2.3.4");

        verify(attempts).save(any(OrderRecoveryAttempt.class));
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
        verify(tokens, never()).save(any());
    }

    @Test
    void requestLink_paid_order_sends_email_and_persists_token() {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(2000);
        o.setStripePaymentIntentId("pi_1");
        when(orders.findRecentForRecovery(eq("buyer@example.com"), any(), any()))
            .thenReturn(List.of(o));

        service.requestLink("buyer@example.com", "1.2.3.4");

        verify(tokens).save(any(RefundRequestToken.class));
        verify(email).send(eq("buyer@example.com"), anyString(), anyString(), anyString());
    }

    @Test
    void requestLink_skips_free_orders() {
        Order free = new Order();
        free.setId(UUID.randomUUID());
        free.setEmail("buyer@example.com");
        free.setTotalMinor(0);
        free.setStripePaymentIntentId(null);
        when(orders.findRecentForRecovery(eq("buyer@example.com"), any(), any()))
            .thenReturn(List.of(free));

        service.requestLink("buyer@example.com", "1.2.3.4");

        verify(tokens, never()).save(any());
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void requestLink_rate_limited_by_email_is_silent() {
        when(attempts.countByEmailAndAttemptedAtAfter(eq("buyer@example.com"), any()))
            .thenReturn(100L);

        service.requestLink("buyer@example.com", "1.2.3.4");

        verify(attempts).save(any(OrderRecoveryAttempt.class));
        verify(tokens, never()).save(any());
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }
}
