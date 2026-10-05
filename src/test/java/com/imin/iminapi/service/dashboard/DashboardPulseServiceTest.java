package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardPulseResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DashboardPulseServiceTest {

    EventRepository events = mock(EventRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    TicketRepository tickets = mock(TicketRepository.class);
    DashboardPulseService sut = new DashboardPulseService(events, orders, tickets);

    UUID orgId = UUID.randomUUID();
    AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());

    private Event event(UUID org, String name) {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(org);
        e.setName(name);
        return e;
    }

    private Order order(UUID eventId, Instant at) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setCreatedAt(at);
        return o;
    }

    private Ticket ticket(Order o, String tier, String state) {
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setOrderId(o.getId());
        t.setEventId(o.getEventId());
        t.setTierName(tier);
        t.setState(state);
        return t;
    }

    private void orgOrders(Order... os) {
        when(orders.findByOrgIdOrderByCreatedAtDesc(eq(orgId), any())).thenReturn(List.of(os));
    }

    private void ticketsAre(Ticket... ts) {
        when(tickets.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(any())).thenReturn(List.of(ts));
    }

    @Test
    void org_scope_with_no_orders_and_nothing_on_sale_is_empty() {
        when(events.countOnSaleByOrg(eq(orgId), any())).thenReturn(0L);
        orgOrders();

        DashboardPulseResponse r = sut.pulse(p, null);

        assertThat(r.onSale()).isFalse();
        assertThat(r.onSaleCount()).isZero();
        assertThat(r.lastSale()).isNull();
        verify(tickets, never()).findByOrderIdInOrderByOrderIdAscCreatedAtAsc(any());
    }

    @Test
    void org_scope_newest_order_with_a_live_ticket_is_the_last_sale() {
        Event e = event(orgId, "Vechirka");
        Instant at = Instant.parse("2026-09-18T20:15:00Z");
        Order o = order(e.getId(), at);
        when(events.countOnSaleByOrg(eq(orgId), any())).thenReturn(2L);
        orgOrders(o);
        ticketsAre(ticket(o, "GA", Ticket.STATE_ISSUED));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        DashboardPulseResponse r = sut.pulse(p, null);

        assertThat(r.onSale()).isTrue();
        assertThat(r.onSaleCount()).isEqualTo(2);
        assertThat(r.lastSale().at()).isEqualTo(at);
        assertThat(r.lastSale().eventId()).isEqualTo(e.getId());
        assertThat(r.lastSale().eventName()).isEqualTo("Vechirka");
        assertThat(r.lastSale().tierNames()).containsExactly("GA");
        assertThat(r.lastSale().ticketCount()).isEqualTo(1);
    }

    @Test
    void fully_refunded_newest_order_falls_through_to_the_next_older_one() {
        Event e = event(orgId, "Night");
        Order newest = order(e.getId(), Instant.parse("2026-09-18T20:00:00Z"));
        Order older = order(e.getId(), Instant.parse("2026-09-17T20:00:00Z"));
        orgOrders(newest, older);
        ticketsAre(ticket(newest, "VIP", Ticket.STATE_REFUNDED), ticket(older, "GA", Ticket.STATE_ISSUED));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        DashboardPulseResponse r = sut.pulse(p, null);

        assertThat(r.lastSale().at()).isEqualTo(older.getCreatedAt());
        assertThat(r.lastSale().tierNames()).containsExactly("GA");
    }

    @Test
    void order_whose_event_is_no_longer_active_is_skipped() {
        Event gone = event(orgId, "Deleted");
        Event kept = event(orgId, "Kept");
        Order newest = order(gone.getId(), Instant.parse("2026-09-18T20:00:00Z"));
        Order older = order(kept.getId(), Instant.parse("2026-09-17T20:00:00Z"));
        orgOrders(newest, older);
        ticketsAre(ticket(newest, "GA", Ticket.STATE_ISSUED), ticket(older, "GA", Ticket.STATE_ISSUED));
        when(events.findActive(gone.getId())).thenReturn(Optional.empty());
        when(events.findActive(kept.getId())).thenReturn(Optional.of(kept));

        DashboardPulseResponse r = sut.pulse(p, null);

        assertThat(r.lastSale().eventId()).isEqualTo(kept.getId());
        assertThat(r.lastSale().eventName()).isEqualTo("Kept");
    }

    @Test
    void tier_names_are_distinct_in_ticket_order_and_refunded_tickets_are_not_counted() {
        Event e = event(orgId, "Night");
        Order o = order(e.getId(), Instant.parse("2026-09-18T20:00:00Z"));
        orgOrders(o);
        ticketsAre(
                ticket(o, "Late", Ticket.STATE_ISSUED),
                ticket(o, "Early Bird", Ticket.STATE_REDEEMED),
                ticket(o, "Late", Ticket.STATE_ISSUED),
                ticket(o, "VIP", Ticket.STATE_REFUNDED));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        DashboardPulseResponse r = sut.pulse(p, null);

        assertThat(r.lastSale().tierNames()).containsExactly("Late", "Early Bird");
        assertThat(r.lastSale().ticketCount()).isEqualTo(3);
    }

    @Test
    void event_scope_uses_the_event_queries() {
        Event e = event(orgId, "Party Na Haty");
        Order o = order(e.getId(), Instant.parse("2026-05-25T19:37:00Z"));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.countOnSaleById(eq(e.getId()), any())).thenReturn(0L);
        when(orders.findByEventIdOrderByCreatedAtDesc(eq(e.getId()), any(Pageable.class)))
                .thenReturn(List.of(o));
        ticketsAre(ticket(o, "Early Bird", Ticket.STATE_ISSUED));

        DashboardPulseResponse r = sut.pulse(p, e.getId());

        assertThat(r.onSale()).isFalse();
        assertThat(r.onSaleCount()).isZero();
        assertThat(r.lastSale().eventName()).isEqualTo("Party Na Haty");
        assertThat(r.lastSale().tierNames()).containsExactly("Early Bird");
        verify(events).countOnSaleById(eq(e.getId()), any());
        verify(events, never()).countOnSaleByOrg(any(), any());
        verify(orders, never()).findByOrgIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void event_of_another_org_is_not_found_and_no_order_query_runs() {
        Event foreign = event(UUID.randomUUID(), "Theirs");
        when(events.findActive(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> sut.pulse(p, foreign.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(orders, tickets);
        verify(events, never()).countOnSaleById(any(), any());
    }

    @Test
    void missing_event_is_not_found() {
        UUID id = UUID.randomUUID();
        when(events.findActive(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.pulse(p, id))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(orders, tickets);
    }
}
