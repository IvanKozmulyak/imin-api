package com.imin.iminapi.refund;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.OrderRecoveryAttempt;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.ticket.TicketProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    TicketRepository tickets = mock(TicketRepository.class);
    RefundTicketRepository refundTickets = mock(RefundTicketRepository.class);
    TicketTierRepository tiers = mock(TicketTierRepository.class);
    RefundService refundService = mock(RefundService.class);

    RefundRequestService service;

    @BeforeEach
    void setUp() {
        emailProps.setBuyerSiteBaseUrl("https://app.test");
        emailProps.setFromAddress("noreply@test");
        ticketProps.setRecoveryMaxPerHour(5);
        when(renderer.render(anyString(), any()))
            .thenReturn(new EmailTemplateRenderer.Rendered("<html/>", "txt"));
        service = new RefundRequestService(orders, attempts, tokens, requests,
            email, renderer, emailProps, ticketProps, publisher,
            tickets, refundTickets, tiers, refundService);
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

    @org.junit.jupiter.api.Nested
    class LookupByToken {

        UUID orderId;
        Order o;
        RefundRequestToken token;

        @BeforeEach
        void seed() {
            orderId = UUID.randomUUID();
            o = new Order();
            o.setId(orderId);
            o.setEmail("buyer@example.com");
            o.setEventId(UUID.randomUUID());
            o.setTotalMinor(8000);
            o.setCurrency("eur");
            o.setStripePaymentIntentId("pi_x");

            token = new RefundRequestToken();
            token.setOrderId(orderId);
            token.setEmailNormalized("buyer@example.com");
            token.setExpiresAt(Instant.now().plusSeconds(600));
        }

        @Test
        void returns_410_when_token_unknown() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

            com.imin.iminapi.security.ApiException ex = assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.lookupByToken("any"));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED);
        }

        @Test
        void returns_410_when_expired() {
            token.setExpiresAt(Instant.now().minusSeconds(60));
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            com.imin.iminapi.security.ApiException ex = assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.lookupByToken("raw"));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED);
        }

        @Test
        void returns_410_when_consumed() {
            token.setConsumedAt(Instant.now().minusSeconds(60));
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            com.imin.iminapi.security.ApiException ex = assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.lookupByToken("raw"));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED);
        }

        @Test
        void returns_409_when_no_refundable_tickets() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of());

            com.imin.iminapi.security.ApiException ex = assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.lookupByToken("raw"));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.NO_REFUNDABLE_TICKETS);
        }

        @Test
        void returns_form_data_for_valid_token() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(UUID.randomUUID());
            t.setOrderId(orderId);
            t.setTierId(UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(refundService.computeRefundAmountMinor(eq(o), org.mockito.ArgumentMatchers.anyList())).thenReturn(2000L);

            var resp = service.lookupByToken("raw");

            assertThat(resp.estimatedRefundMinor()).isEqualTo(2000L);
            assertThat(resp.tickets()).hasSize(1);
            assertThat(resp.reasons()).contains("cant_attend", "other");
        }
    }

    @org.junit.jupiter.api.Nested
    class SubmitByToken {

        UUID orderId;
        Order o;
        RefundRequestToken token;

        @BeforeEach
        void seed() {
            orderId = UUID.randomUUID();
            o = new Order();
            o.setId(orderId);
            o.setOrgId(UUID.randomUUID());
            o.setEventId(UUID.randomUUID());
            o.setEmail("buyer@example.com");
            o.setTotalMinor(8000);
            o.setCurrency("eur");

            token = new RefundRequestToken();
            token.setOrderId(orderId);
            token.setEmailNormalized("buyer@example.com");
            token.setExpiresAt(Instant.now().plusSeconds(600));
        }

        com.imin.iminapi.refund.dto.PublicRefundSubmitRequest req() {
            return new com.imin.iminapi.refund.dto.PublicRefundSubmitRequest(
                RefundRequestReason.CANT_ATTEND, "Can't make it", null);
        }

        @Test
        void submits_writes_request_burns_token_and_publishes_event() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(UUID.randomUUID());
            t.setOrderId(orderId);
            t.setTierId(UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(requests.existsByOrderIdAndStatus(orderId, RefundRequestStatus.PENDING)).thenReturn(false);
            when(requests.save(any())).thenAnswer(inv -> {
                RefundRequest rr = inv.getArgument(0);
                rr.setId(UUID.randomUUID());
                return rr;
            });

            var resp = service.submitByToken("raw", req());

            assertThat(resp.status()).isEqualTo("pending");
            verify(requests).save(any(RefundRequest.class));
            verify(tokens).save(org.mockito.ArgumentMatchers.argThat(t2 -> t2.getConsumedAt() != null));
            verify(publisher).publishEvent(any(com.imin.iminapi.refund.event.RefundRequestSubmittedEvent.class));
        }

        @Test
        void submits_returns_409_when_a_pending_request_already_exists() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(UUID.randomUUID());
            t.setOrderId(orderId);
            t.setTierId(UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(requests.existsByOrderIdAndStatus(orderId, RefundRequestStatus.PENDING)).thenReturn(true);

            com.imin.iminapi.security.ApiException ex = assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.submitByToken("raw", req()));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_REQUEST_ALREADY_OPEN);
        }
    }

    @org.junit.jupiter.api.Nested
    class GetRequestForOrganizer {

        @Test
        void returns_404_when_request_belongs_to_other_org() {
            java.util.UUID rid = java.util.UUID.randomUUID();
            when(requests.findByIdAndOrgId(eq(rid), any()))
                .thenReturn(Optional.empty());

            com.imin.iminapi.security.ApiException ex = assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.getRequest(rid, java.util.UUID.randomUUID()));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.NOT_FOUND);
        }

        @Test
        void returns_detail_with_proposed_refund_when_pending_and_refundable() {
            java.util.UUID rid = java.util.UUID.randomUUID();
            java.util.UUID orgId = java.util.UUID.randomUUID();
            Order order = new Order();
            order.setId(java.util.UUID.randomUUID());
            order.setOrgId(orgId);
            order.setEventId(java.util.UUID.randomUUID());
            order.setTotalMinor(8000);
            order.setCurrency("eur");
            order.setStripePaymentIntentId("pi_x");

            RefundRequest rr = new RefundRequest();
            rr.setId(rid);
            rr.setOrgId(orgId);
            rr.setOrderId(order.getId());
            rr.setEventId(order.getEventId());
            rr.setBuyerEmail("buyer@example.com");
            rr.setReason(RefundRequestReason.CANT_ATTEND);
            rr.setExplanation("Can't make it");
            rr.setStatus(RefundRequestStatus.PENDING);

            when(requests.findByIdAndOrgId(rid, orgId)).thenReturn(Optional.of(rr));
            when(orders.findById(order.getId())).thenReturn(Optional.of(order));

            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(java.util.UUID.randomUUID());
            t.setOrderId(order.getId());
            t.setTierId(java.util.UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(order.getId())).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(refundService.computeRefundAmountMinor(eq(order), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(8000L);
            when(refundService.computeAppFeeRefundMinor(order, 8000L)).thenReturn(400L);

            var resp = service.getRequest(rid, orgId);

            assertThat(resp.id()).isEqualTo(rid);
            assertThat(resp.status()).isEqualTo("pending");
            assertThat(resp.reason()).isEqualTo("cant_attend");
            assertThat(resp.tickets()).hasSize(1);
            assertThat(resp.proposedRefund()).isNotNull();
            assertThat(resp.proposedRefund().amountMinor()).isEqualTo(8000L);
            assertThat(resp.proposedRefund().appFeeRefundMinor()).isEqualTo(400L);
        }

        @Test
        void returns_detail_without_proposed_refund_when_already_decided() {
            java.util.UUID rid = java.util.UUID.randomUUID();
            java.util.UUID orgId = java.util.UUID.randomUUID();
            Order order = new Order();
            order.setId(java.util.UUID.randomUUID());
            order.setOrgId(orgId);
            order.setEventId(java.util.UUID.randomUUID());
            order.setTotalMinor(8000);
            order.setCurrency("eur");

            RefundRequest rr = new RefundRequest();
            rr.setId(rid);
            rr.setOrgId(orgId);
            rr.setOrderId(order.getId());
            rr.setEventId(order.getEventId());
            rr.setBuyerEmail("buyer@example.com");
            rr.setReason(RefundRequestReason.OTHER);
            rr.setExplanation("...");
            rr.setStatus(RefundRequestStatus.REJECTED);

            when(requests.findByIdAndOrgId(rid, orgId)).thenReturn(Optional.of(rr));
            when(orders.findById(order.getId())).thenReturn(Optional.of(order));
            when(tickets.findByOrderId(order.getId())).thenReturn(List.of());
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());

            var resp = service.getRequest(rid, orgId);

            assertThat(resp.status()).isEqualTo("rejected");
            assertThat(resp.proposedRefund()).isNull();
        }
    }

    @org.junit.jupiter.api.Nested
    class ListRequestsForOrganizer {

        @Test
        void returns_empty_when_no_rows_match() {
            java.util.UUID orgId = java.util.UUID.randomUUID();
            java.util.UUID eventId = java.util.UUID.randomUUID();
            when(requests.page(eq(orgId), eq(eventId),
                eq(List.of(RefundRequestStatus.PENDING)),
                any(), any(), any())).thenReturn(List.of());

            var page = service.listRequests(orgId, eventId,
                List.of(RefundRequestStatus.PENDING), null, 25);
            assertThat(page).isEmpty();
        }

        @Test
        void maps_rows_to_summary_responses() {
            java.util.UUID orgId = java.util.UUID.randomUUID();
            Order order = new Order();
            order.setId(java.util.UUID.randomUUID());
            order.setOrgId(orgId);
            order.setEventId(java.util.UUID.randomUUID());
            order.setTotalMinor(5000);
            order.setCurrency("eur");

            RefundRequest rr = new RefundRequest();
            rr.setId(java.util.UUID.randomUUID());
            rr.setOrgId(orgId);
            rr.setOrderId(order.getId());
            rr.setEventId(order.getEventId());
            rr.setBuyerEmail("buyer@example.com");
            rr.setReason(RefundRequestReason.CANT_ATTEND);
            rr.setExplanation("...");
            rr.setStatus(RefundRequestStatus.PENDING);

            when(requests.page(eq(orgId), any(), any(), any(), any(), any()))
                .thenReturn(List.of(rr));

            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(java.util.UUID.randomUUID());
            t.setOrderId(order.getId());
            t.setTierId(java.util.UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(order.getId())).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(orders.findById(order.getId())).thenReturn(Optional.of(order));
            when(refundService.computeRefundAmountMinor(eq(order), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(2500L);

            var page = service.listRequests(orgId, null, null, null, 25);
            assertThat(page).hasSize(1);
            assertThat(page.get(0).status()).isEqualTo("pending");
            assertThat(page.get(0).reason()).isEqualTo("cant_attend");
            assertThat(page.get(0).ticketCount()).isEqualTo(1);
            assertThat(page.get(0).estimatedRefundMinor()).isEqualTo(2500L);
            assertThat(page.get(0).currency()).isEqualTo("eur");
        }
    }
}
