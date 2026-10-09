package com.imin.iminapi.email;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundRequest;
import com.imin.iminapi.refund.RefundRequestReason;
import com.imin.iminapi.refund.RefundRequestRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.refund.RefundTicketRepository;
import com.imin.iminapi.refund.email.RefundConfirmationEmailer;
import com.imin.iminapi.refund.email.RefundRequestEmailer;
import com.imin.iminapi.refund.event.RefundRequestSubmittedEvent;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.IpHasher;
import com.imin.iminapi.service.ticket.OrderRecoveryService;
import com.imin.iminapi.service.ticket.TicketProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real templates and real emailers: the order number is the only identifier shown as text, in every locale. */
class OrderNumberInEmailsTest {

    private final EmailService email = mock(EmailService.class);
    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();
    private final UUID eventId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private Event event() {
        Event e = new Event();
        e.setId(eventId);
        e.setName("Saturn Night");
        e.setTimezone("Europe/Paris");
        return e;
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "es", "fr", "uk"})
    void refundConfirmation_showsTheOrderNumber(String locale) {
        RefundRepository refunds = mock(RefundRepository.class);
        RefundTicketRepository refundTickets = mock(RefundTicketRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        EventRepository events = mock(EventRepository.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        UUID refundId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

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
        order.setBuyerLocale(locale);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event()));
        Organization org = new Organization();
        org.setId(orgId);
        org.setContactEmail("hello@organizer.example");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));
        when(refundTickets.findTicketIdsByRefundId(refundId)).thenReturn(List.of(UUID.randomUUID()));

        new RefundConfirmationEmailer(refunds, refundTickets, orders, events, orgs, email, renderer)
                .onRefundConfirmed(new com.imin.iminapi.refund.event.RefundConfirmedEvent(refundId));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("buyer@example.com"), subject.capture(), html.capture(), text.capture());
        String number = "#" + orderId.toString().substring(0, 8);
        assertThat(html.getValue()).contains("<strong>" + number + "</strong>");
        assertThat(text.getValue()).contains(number);
        assertThat(subject.getValue() + html.getValue() + text.getValue())
                .doesNotContain(orderId.toString()).contains("Saturn Night").contains("50.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "es", "fr", "uk"})
    void refundRequestAck_showsTheOrderNumber_notTheRequestId(String locale) {
        RefundRequestRepository requests = mock(RefundRequestRepository.class);
        EventRepository events = mock(EventRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        UserRepository users = mock(UserRepository.class);
        UUID requestId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        RefundRequest rr = new RefundRequest();
        rr.setId(requestId);
        rr.setOrderId(orderId);
        rr.setOrgId(orgId);
        rr.setEventId(eventId);
        rr.setBuyerEmail("buyer@example.com");
        rr.setReason(RefundRequestReason.CANT_ATTEND);
        rr.setExplanation("Cannot make it");
        when(requests.findById(requestId)).thenReturn(Optional.of(rr));
        when(events.findById(eventId)).thenReturn(Optional.of(event()));
        Order order = new Order();
        order.setId(orderId);
        order.setBuyerLocale(locale);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(orgs.findById(orgId)).thenReturn(Optional.empty());
        when(users.findByOrgIdOrderByCreatedAtAsc(orgId)).thenReturn(List.of());

        new RefundRequestEmailer(email, renderer, new EmailProperties(), requests, events, orders, orgs, users)
                .onSubmitted(new RefundRequestSubmittedEvent(requestId));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("buyer@example.com"), anyString(), html.capture(), text.capture());
        String number = "#" + orderId.toString().substring(0, 8);
        assertThat(html.getValue()).contains(number);
        assertThat(text.getValue()).contains(number);
        assertThat(html.getValue() + text.getValue())
                .doesNotContain(requestId.toString()).doesNotContain(requestId.toString().substring(0, 8));
    }

    @Test
    void orderRecovery_labelsEachOrderByEventDateAndOrderNumber_notByTokenText() {
        OrderRepository orders = mock(OrderRepository.class);
        EventRepository events = mock(EventRepository.class);
        OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);
        when(attempts.countByEmailAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);
        when(attempts.countByIpHashAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);
        when(events.findById(eventId)).thenReturn(Optional.of(event()));
        Order o = new Order();
        UUID orderId = UUID.randomUUID();
        String number = "#" + orderId.toString().substring(0, 8);
        o.setId(orderId);
        o.setToken("ORDTOK123");
        o.setEmail("buyer@example.com");
        o.setEventId(eventId);
        // 22:30Z on 8 Oct is already 9 Oct in Paris.
        o.setCreatedAt(Instant.parse("2026-10-08T22:30:00Z"));
        when(orders.findRecentForRecovery(eq("buyer@example.com"), isNull(), any())).thenReturn(List.of(o));
        EmailProperties ep = new EmailProperties();
        ep.setBuyerSiteBaseUrl("https://app.imin.wtf");
        TicketProperties tp = new TicketProperties();
        tp.setSigningSecret("x".repeat(32));
        tp.setRecoveryWindowDays(90);
        tp.setRecoveryMaxPerHour(5);

        new OrderRecoveryService(orders, events, email, renderer, ep, tp, attempts, new IpHasher("test-ip-hash-secret"))
                .requestRecovery("buyer@example.com", null, "1.2.3.4");

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("buyer@example.com"), anyString(), html.capture(), text.capture());
        assertThat(html.getValue())
                .contains("<a href=\"https://app.imin.wtf/order/ORDTOK123\"")
                .contains(">Saturn Night · 9 Oct 2026 · Order " + number + "</a>")
                .doesNotContain(">https://app.imin.wtf/order/");
        assertThat(text.getValue())
                .contains("- Saturn Night · 9 Oct 2026 · Order " + number + "\n  https://app.imin.wtf/order/ORDTOK123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "es", "fr", "uk"})
    void ticketConfirmation_showsTheOrderNumber_notTheOrderOrTicketToken(String locale) {
        OrderRepository orders = mock(OrderRepository.class);
        EventRepository events = mock(EventRepository.class);
        com.imin.iminapi.repository.TicketRepository tickets = mock(com.imin.iminapi.repository.TicketRepository.class);
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setToken("ORDTOK123");
        order.setEventId(eventId);
        order.setEmail("buyer@example.com");
        order.setBuyerLocale(locale);
        com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
        t.setToken("TKTTOK456");
        t.setTierName("GA");
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event()));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(t));
        EmailProperties ep = new EmailProperties();
        ep.setBuyerSiteBaseUrl("https://app.imin.wtf");
        TicketProperties tp = new TicketProperties();
        tp.setSigningSecret("x".repeat(32));
        tp.setApiPublicBaseUrl("https://api.imin.test");
        var apple = mock(com.imin.iminapi.service.ticket.AppleWalletPassService.class);
        var google = mock(com.imin.iminapi.service.ticket.google.GoogleWalletPassService.class);

        new com.imin.iminapi.service.ticket.TicketIssuanceEmailer(orders, tickets, events, email, renderer, ep, tp,
                new com.imin.iminapi.service.ticket.WalletOffers(apple, google, tp)).send(orderId);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("buyer@example.com"), anyString(), html.capture(), text.capture());
        String number = "#" + orderId.toString().substring(0, 8);
        assertThat(html.getValue()).contains(number);
        assertThat(text.getValue()).contains(number);
        // Tokens appear only inside links, never as visible text.
        assertThat(html.getValue()).doesNotContain(">ORDTOK123<").doesNotContain(">TKTTOK456<")
                .doesNotContain(orderId.toString());
        assertThat(text.getValue()).doesNotContain(orderId.toString());
    }
}
