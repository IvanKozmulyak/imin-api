package com.imin.iminapi.refund.email;

import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.refund.RefundTicketRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundConfirmationEmailerTest {

    RefundRepository refunds = mock(RefundRepository.class);
    RefundTicketRepository refundTickets = mock(RefundTicketRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    EventRepository events = mock(EventRepository.class);
    OrganizationRepository orgs = mock(OrganizationRepository.class);
    EmailService emailService = mock(EmailService.class);
    EmailTemplateRenderer renderer = mock(EmailTemplateRenderer.class);

    RefundConfirmationEmailer emailer;

    UUID refundId;
    UUID orderId;
    UUID eventId;
    UUID orgId;

    @BeforeEach
    void setUp() {
        refundId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        emailer = new RefundConfirmationEmailer(refunds, refundTickets, orders, events, orgs, emailService, renderer);
    }

    @Test
    void send_buildsEmailFromRefundOrderEventOrganization() {
        Refund r = new Refund();
        r.setId(refundId);
        r.setOrderId(orderId);
        r.setAmountMinor(5000);
        r.setCurrency("eur");
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setReason(RefundReason.REQUESTED_BY_CUSTOMER);
        when(refunds.findById(refundId)).thenReturn(Optional.of(r));

        Order order = new Order();
        order.setId(orderId);
        order.setEmail("buyer@example.com");
        order.setEventId(eventId);
        order.setOrgId(orgId);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));

        Event event = new Event();
        event.setId(eventId);
        event.setName("Saturn Night");
        when(events.findById(eventId)).thenReturn(Optional.of(event));

        Organization org = new Organization();
        org.setId(orgId);
        org.setContactEmail("hello@organizer.example");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        when(refundTickets.findTicketIdsByRefundId(refundId))
            .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        when(renderer.render(eq("refund-confirmed"), any(), any()))
            .thenReturn(new EmailTemplateRenderer.Rendered("<html>r</html>", "text"));

        emailer.send(refundId);

        ArgumentCaptor<String> toCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(toCap.capture(), subjCap.capture(), any(), any());
        assertThat(toCap.getValue()).isEqualTo("buyer@example.com");
        assertThat(subjCap.getValue()).contains("Saturn Night");
    }

    /**
     * W1.G: the refund email follows the language the buyer bought in
     * (orders.buyer_locale, V78) — both the template variant and the subject line.
     */
    @Test
    void send_rendersWithTheBuyersLocale_andLocalizedSubject() {
        seedRefundFor("fr");

        when(renderer.render(eq("refund-confirmed"), eq("fr"), any()))
            .thenReturn(new EmailTemplateRenderer.Rendered("<html>fr</html>", "fr"));

        emailer.send(refundId);

        verify(renderer).render(eq("refund-confirmed"), eq("fr"), any());
        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq("buyer@example.com"), subj.capture(), any(), any());
        assertThat(subj.getValue()).isEqualTo("Remboursement confirmé pour Saturn Night");
    }

    /** No stored preference ⇒ null locale reaches the renderer, which means English. */
    @Test
    void send_passesNullLocale_whenOrderHasNone() {
        seedRefundFor(null);

        when(renderer.render(eq("refund-confirmed"), eq((String) null), any()))
            .thenReturn(new EmailTemplateRenderer.Rendered("<html>en</html>", "en"));

        emailer.send(refundId);

        verify(renderer).render(eq("refund-confirmed"), eq((String) null), any());
        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq("buyer@example.com"), subj.capture(), any(), any());
        assertThat(subj.getValue()).isEqualTo("Refund confirmed for Saturn Night");
    }

    /** Full happy-path fixture with a given buyer locale on the order. */
    private void seedRefundFor(String buyerLocale) {
        Refund r = new Refund();
        r.setId(refundId);
        r.setOrderId(orderId);
        r.setAmountMinor(5000);
        r.setCurrency("eur");
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setReason(RefundReason.REQUESTED_BY_CUSTOMER);
        when(refunds.findById(refundId)).thenReturn(Optional.of(r));

        Order order = new Order();
        order.setId(orderId);
        order.setEmail("buyer@example.com");
        order.setEventId(eventId);
        order.setOrgId(orgId);
        order.setBuyerLocale(buyerLocale);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));

        Event event = new Event();
        event.setId(eventId);
        event.setName("Saturn Night");
        when(events.findById(eventId)).thenReturn(Optional.of(event));

        Organization org = new Organization();
        org.setId(orgId);
        org.setContactEmail("hello@organizer.example");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        when(refundTickets.findTicketIdsByRefundId(refundId)).thenReturn(List.of(UUID.randomUUID()));
    }

    @Test
    void send_missingRefund_logsAndSkips() {
        when(refunds.findById(refundId)).thenReturn(Optional.empty());
        emailer.send(refundId);
        verify(emailService, never()).send(any(), any(), any(), any());
    }

    @Test
    void send_missingOrderEmail_skips() {
        Refund r = new Refund();
        r.setId(refundId);
        r.setOrderId(orderId);
        when(refunds.findById(refundId)).thenReturn(Optional.of(r));
        Order order = new Order();
        order.setId(orderId);
        order.setEmail(null);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));

        emailer.send(refundId);
        verify(emailService, never()).send(any(), any(), any(), any());
    }
}
