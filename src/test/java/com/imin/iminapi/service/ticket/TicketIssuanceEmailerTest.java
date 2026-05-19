package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketIssuanceEmailerTest {

    @Test
    void renders_email_with_per_ticket_qr_link_order_link_and_recover_link() {
        EmailService email = mock(EmailService.class);
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();
        OrderRepository orders = mock(OrderRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        EventRepository events = mock(EventRepository.class);
        EmailProperties emailProps = new EmailProperties();
        emailProps.setAppBaseUrl("https://imin.wtf");
        AppleWalletPassService wallet = mock(AppleWalletPassService.class);
        when(wallet.isConfigured()).thenReturn(true);

        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setToken("ORDER_TOK");
        order.setEventId(eventId);
        order.setEmail("buyer@example.com");

        Event event = new Event();
        event.setId(eventId);
        event.setName("Saturn Night");
        event.setStartsAt(OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant());
        event.setTimezone("Europe/Paris");
        event.setVenueName("Le Petit Bain");
        event.setVenueCity("Paris");

        Ticket t1 = new Ticket();
        t1.setToken("TKT_A");
        t1.setTierName("GA");
        Ticket t2 = new Ticket();
        t2.setToken("TKT_B");
        t2.setTierName("GA");

        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(t1, t2));

        TicketIssuanceEmailer emailer = new TicketIssuanceEmailer(
                orders, tickets, events, email, renderer, emailProps, wallet);
        emailer.send(orderId);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("buyer@example.com"), subject.capture(), html.capture(), text.capture());

        assertThat(subject.getValue()).isEqualTo("Your tickets for Saturn Night");
        assertThat(html.getValue())
                .contains("/api/v1/public/tickets/TKT_A/qr.png")
                .contains("/api/v1/public/tickets/TKT_B/qr.png")
                .contains("/api/v1/public/tickets/TKT_A/apple-wallet.pkpass")
                .contains("/api/v1/public/tickets/TKT_B/apple-wallet.pkpass")
                .contains("/order/ORDER_TOK")
                .contains("/recover");
        assertThat(text.getValue())
                .contains("/tickets/TKT_A")
                .contains("/tickets/TKT_B")
                .contains("/order/ORDER_TOK")
                .contains("/recover");
    }

    @Test
    void single_ticket_uses_singular_subject_and_omits_wallet_when_unconfigured() {
        EmailService email = mock(EmailService.class);
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();
        OrderRepository orders = mock(OrderRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        EventRepository events = mock(EventRepository.class);
        EmailProperties emailProps = new EmailProperties();
        emailProps.setAppBaseUrl("https://imin.wtf");
        AppleWalletPassService wallet = mock(AppleWalletPassService.class);
        when(wallet.isConfigured()).thenReturn(false);

        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setToken("ORDER_X");
        order.setEventId(eventId);
        order.setEmail("solo@example.com");

        Event event = new Event();
        event.setId(eventId);
        event.setName("Helios");

        Ticket t = new Ticket();
        t.setToken("TKT_SOLO");
        t.setTierName("GA");

        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(t));

        TicketIssuanceEmailer emailer = new TicketIssuanceEmailer(
                orders, tickets, events, email, renderer, emailProps, wallet);
        emailer.send(orderId);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("solo@example.com"), subject.capture(), html.capture(), any());

        assertThat(subject.getValue()).isEqualTo("Your ticket for Helios");
        // No wallet → no wallet link
        assertThat(html.getValue()).doesNotContain("apple-wallet.pkpass");
    }
}
