package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.service.MembershipProjector;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MembershipProjector} — what {@code no_show} and {@code attended}
 * are actually derived from, and which queries the projection is allowed to issue.
 *
 * <p>Mockito rather than {@code @SpringBootTest}: several of these assert on which
 * repository calls happen, which an integration test cannot observe.
 */
class MembershipProjectorTest {

    private OrderRepository orderRepo;
    private TicketRepository ticketRepo;
    private EventRepository eventRepo;
    private MembershipProjector projector;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orderRepo = mock(OrderRepository.class);
        ticketRepo = mock(TicketRepository.class);
        eventRepo = mock(EventRepository.class);
        projector = new MembershipProjector(orderRepo, ticketRepo, eventRepo);
    }

    /**
     * audience-6: recompute() used to run the dashboard's whole-org group-by-email order
     * aggregate on every projection and never read the result. It fires on every ticket
     * issue, every redeem, every CSV import row and every backfill pair.
     */
    @Test
    void recompute_does_not_run_the_whole_org_order_aggregate() {
        stubOrder();

        projector.recompute(membership(), "buyer@x.com");

        verify(orderRepo, never()).orderCountsByEmailSince(any(), any());
    }

    /**
     * audience-3: an ISSUED ticket for an event that has not happened yet is not a
     * no-show. The prebuilt "Bought-no-showed" segment is a live campaign target, so
     * this used to mail every buyer holding an upcoming ticket.
     */
    @Test
    void issued_ticket_for_a_future_event_is_not_a_no_show() {
        UUID eventId = UUID.randomUUID();
        Order order = order(eventId);
        stubOrder(order);
        stubTickets(ticket(order, Ticket.STATE_ISSUED));
        stubEvents(event(eventId, Instant.now().plus(30, ChronoUnit.DAYS), null));

        Membership m = membership();
        projector.recompute(m, "buyer@x.com");

        assertThat(m.getNoShow()).isZero();
    }

    @Test
    void issued_ticket_for_a_past_event_is_a_no_show() {
        UUID eventId = UUID.randomUUID();
        Order order = order(eventId);
        stubOrder(order);
        stubTickets(ticket(order, Ticket.STATE_ISSUED));
        stubEvents(event(eventId, Instant.now().minus(10, ChronoUnit.DAYS),
                Instant.now().minus(9, ChronoUnit.DAYS)));

        Membership m = membership();
        projector.recompute(m, "buyer@x.com");

        assertThat(m.getNoShow()).isEqualTo(1);
    }

    /** An event that is under way — started, not yet ended — has not been missed yet. */
    @Test
    void issued_ticket_for_an_event_still_running_is_not_a_no_show() {
        UUID eventId = UUID.randomUUID();
        Order order = order(eventId);
        stubOrder(order);
        stubTickets(ticket(order, Ticket.STATE_ISSUED));
        stubEvents(event(eventId, Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(3, ChronoUnit.HOURS)));

        Membership m = membership();
        projector.recompute(m, "buyer@x.com");

        assertThat(m.getNoShow()).isZero();
    }

    /** audience-3: no_show counts EVENTS, not tickets — four tickets to one past gig is 1. */
    @Test
    void four_unscanned_tickets_to_one_past_event_count_as_one_no_show() {
        UUID eventId = UUID.randomUUID();
        Order order = order(eventId);
        stubOrder(order);
        stubTickets(ticket(order, Ticket.STATE_ISSUED), ticket(order, Ticket.STATE_ISSUED),
                ticket(order, Ticket.STATE_ISSUED), ticket(order, Ticket.STATE_ISSUED));
        stubEvents(event(eventId, Instant.now().minus(10, ChronoUnit.DAYS), null));

        Membership m = membership();
        projector.recompute(m, "buyer@x.com");

        assertThat(m.getNoShow()).isEqualTo(1);
    }

    /** audience-3: turning up on one of two tickets means the buyer attended — not a no-show. */
    @Test
    void a_redeemed_ticket_clears_the_no_show_for_that_event() {
        UUID eventId = UUID.randomUUID();
        Order order = order(eventId);
        Ticket redeemed = ticket(order, Ticket.STATE_REDEEMED);
        redeemed.setRedeemedAt(Instant.now().minus(9, ChronoUnit.DAYS));
        stubOrder(order);
        stubTickets(redeemed, ticket(order, Ticket.STATE_ISSUED));
        stubEvents(event(eventId, Instant.now().minus(10, ChronoUnit.DAYS), null));

        Membership m = membership();
        projector.recompute(m, "buyer@x.com");

        assertThat(m.getNoShow()).isZero();
    }

    /** An event we hold no date for cannot be proven to have passed, so it is not a no-show. */
    @Test
    void issued_ticket_for_an_undated_event_is_not_a_no_show() {
        UUID eventId = UUID.randomUUID();
        Order order = order(eventId);
        stubOrder(order);
        stubTickets(ticket(order, Ticket.STATE_ISSUED));
        stubEvents(event(eventId, null, null));

        Membership m = membership();
        projector.recompute(m, "buyer@x.com");

        assertThat(m.getNoShow()).isZero();
    }

    /**
     * audience-2 (secondary): {@code attended} counts EVENTS attended, not redeemed
     * tickets — otherwise two tickets to one gig already read as a repeat attendee,
     * and {@code attended} could exceed {@code events}.
     */
    @Test
    void two_redeemed_tickets_to_one_event_count_as_one_attended_event() {
        UUID eventId = UUID.randomUUID();
        Order order = order(eventId);
        Ticket first = ticket(order, Ticket.STATE_REDEEMED);
        first.setRedeemedAt(Instant.now().minus(9, ChronoUnit.DAYS));
        Ticket second = ticket(order, Ticket.STATE_REDEEMED);
        second.setRedeemedAt(Instant.now().minus(9, ChronoUnit.DAYS));
        stubOrder(order);
        stubTickets(first, second);
        stubEvents(event(eventId, Instant.now().minus(10, ChronoUnit.DAYS), null));

        Membership m = membership();
        projector.recompute(m, "buyer@x.com");

        assertThat(m.getAttended()).isEqualTo(1);
        assertThat(m.getLastAttended()).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubOrder(Order... orders) {
        when(orderRepo.findByOrgIdAndNormalizedEmail(orgId, "buyer@x.com")).thenReturn(List.of(orders));
    }

    private void stubTickets(Ticket... tickets) {
        when(ticketRepo.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(anyCollection()))
                .thenReturn(List.of(tickets));
    }

    private void stubEvents(Event... events) {
        when(eventRepo.findAllById(anyCollection())).thenReturn(List.of(events));
    }

    private Membership membership() {
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(UUID.randomUUID());
        return m;
    }

    private Order order(UUID eventId) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setOrgId(orgId);
        o.setEventId(eventId);
        o.setEmail("buyer@x.com");
        o.setTotalMinor(1000L);
        o.setCreatedAt(Instant.now().minus(20, ChronoUnit.DAYS));
        return o;
    }

    private Ticket ticket(Order order, String state) {
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setOrderId(order.getId());
        t.setState(state);
        return t;
    }

    private Event event(UUID id, Instant startsAt, Instant endsAt) {
        Event e = new Event();
        e.setId(id);
        e.setOrgId(orgId);
        e.setStartsAt(startsAt);
        e.setEndsAt(endsAt);
        return e;
    }
}
