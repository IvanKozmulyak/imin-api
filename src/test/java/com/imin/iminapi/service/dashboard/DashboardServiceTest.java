package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.dto.event.EventSalesFigures;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.dashboard.DashboardRevenue.Window;
import com.imin.iminapi.service.event.EventSalesTotals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    EventRepository events = mock(EventRepository.class);
    UserRepository users = mock(UserRepository.class);
    AuditLogRepository auditLogs = mock(AuditLogRepository.class);
    EventSalesTotals salesTotals = mock(EventSalesTotals.class);
    DashboardRevenue revenue = mock(DashboardRevenue.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    TicketTierRepository tiers = mock(TicketTierRepository.class);
    DashboardService sut = new DashboardService(events, users, auditLogs, salesTotals, revenue, memberships, tiers);

    @BeforeEach
    void noSalesByDefault() {
        when(salesTotals.forEvent(any())).thenReturn(EventSalesFigures.EMPTY);
    }

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private void stubEmptyAuxiliary(UUID orgId) {
        when(revenue.forOrgWindow(eq(orgId), any(), any())).thenReturn(new Window(0, 0));
        Page<com.imin.iminapi.model.AuditLog> emptyPage = new PageImpl<>(List.of());
        when(auditLogs.findByOrgIdOrderByOccurredAtDesc(eq(orgId), any())).thenReturn(emptyPage);
    }

    @Test
    void empty_org_returns_null_next_and_zeroed_metrics() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        User u = new User();
        u.setId(p.userId()); u.setFirstName("Jaune"); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of());
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of());
        when(events.countLive(orgId)).thenReturn(0L);
        when(events.countPublished(orgId)).thenReturn(0L);
        when(events.countPast(orgId)).thenReturn(0L);
        stubEmptyAuxiliary(orgId);

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);
        assertThat(r.greeting().name()).isEqualTo("Jaune");
        assertThat(r.now().nextEvent()).isNull();
        assertThat(r.now().ticketsTotal()).isZero();
        assertThat(r.cycle().period()).isEqualTo("30d");
        assertThat(r.cycle().activeEvents()).isZero();
        assertThat(r.lastEvent().event()).isNull();
        assertThat(r.lastEvent().metrics().capacity()).isZero();
        assertThat(r.business().totalRevenueMinor()).isZero();
        assertThat(r.activity()).isEmpty();
        assertThat(r.prediction()).isNull();
    }

    @Test
    void populated_org_returns_next_and_last_with_pct_and_daysOut() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        User u = new User(); u.setId(p.userId()); u.setFirstName("Jaune"); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));

        Event next = new Event();
        next.setId(UUID.randomUUID()); next.setOrgId(orgId);
        next.setName("Next Night"); next.setSlug("next-night");
        next.setStartsAt(Instant.now().plusSeconds(28L * 24 * 3600));
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of(next));
        when(salesTotals.forEvent(next.getId())).thenReturn(new EventSalesFigures(57, 100, 0L));

        Event past = new Event();
        past.setId(UUID.randomUUID()); past.setOrgId(orgId);
        past.setName("Last Night"); past.setSlug("last-night");
        past.setStatus(EventStatus.PAST);
        past.setEndsAt(Instant.now().minusSeconds(7L * 24 * 3600));
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of(past));
        when(salesTotals.forEvent(past.getId())).thenReturn(new EventSalesFigures(198, 200, 475_200L));
        when(revenue.netForEvent(past.getId())).thenReturn(475_200L);
        when(revenue.ticketsForEvent(past.getId())).thenReturn(198L);

        when(events.countLive(orgId)).thenReturn(3L);
        when(events.countPublished(orgId)).thenReturn(6L);
        when(events.countPast(orgId)).thenReturn(4L);
        stubEmptyAuxiliary(orgId);

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);
        assertThat(r.now().nextEvent().id()).isEqualTo(next.getId());
        assertThat(r.now().pct()).isEqualTo(57);
        assertThat(r.now().daysOut()).isBetween(27, 28);
        assertThat(r.now().ticketsTotal()).isEqualTo(100);
        assertThat(r.cycle().activeEvents()).isEqualTo(3);
        assertThat(r.lastEvent().event().id()).isEqualTo(past.getId());
        assertThat(r.lastEvent().metrics().attended()).isEqualTo(198);
        assertThat(r.lastEvent().metrics().capacity()).isEqualTo(200);
        assertThat(r.lastEvent().metrics().avgTicketMinor()).isEqualTo(2400);
        assertThat(r.business().eventsPublished()).isEqualTo(6);
        assertThat(r.now().nextEvent().capacity()).isEqualTo(100);
        assertThat(r.lastEvent().event().capacity()).isEqualTo(200);
    }

    /** A tier-quantity sum of 0 is unknown capacity: the embedded EventDto carries null, not 0. */
    @Test
    void event_capacity_is_null_when_the_events_have_no_tier_quantity() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        User u = new User(); u.setId(p.userId()); u.setFirstName("Jaune"); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));

        Event next = new Event();
        next.setId(UUID.randomUUID()); next.setOrgId(orgId);
        next.setName("No Tiers Yet"); next.setSlug("no-tiers-yet");
        next.setStartsAt(Instant.now().plusSeconds(10L * 24 * 3600));
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of(next));

        Event past = new Event();
        past.setId(UUID.randomUUID()); past.setOrgId(orgId);
        past.setName("Tierless Past"); past.setSlug("tierless-past");
        past.setStatus(EventStatus.PAST);
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of(past));
        stubEmptyAuxiliary(orgId);

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);
        assertThat(r.now().nextEvent().capacity()).isNull();
        assertThat(r.lastEvent().event().capacity()).isNull();
        assertThat(r.lastEvent().metrics().capacity()).isZero();
    }

    private AuthPrincipal emptyHome(UUID orgId) {
        AuthPrincipal p = owner(orgId);
        User u = new User(); u.setId(p.userId()); u.setFirstName("Jaune"); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of());
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of());
        stubEmptyAuxiliary(orgId);
        return p;
    }

    /** Stubs the current 30-day cycle window and the one before it, as the service asks for them. */
    private void stubCycle30d(UUID orgId, Window current, Window prior) {
        when(revenue.forOrgWindow(eq(orgId), any(), any())).thenAnswer(inv -> {
            Instant since = inv.getArgument(1);
            Instant until = inv.getArgument(2);
            long days = java.time.Duration.between(since, until).toDays();
            boolean endsNow = java.time.Duration.between(until, Instant.now()).abs().toMinutes() < 1;
            if (days == 30 && endsNow) return current;
            if (days == 30) return prior;
            return new Window(0, 0);
        });
    }

    @Test
    void cycle_worked_example_reads_net_and_tickets_with_deltas_against_the_prior_window() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = emptyHome(orgId);
        stubCycle30d(orgId, new Window(2_502, 3), new Window(3_000, 1));

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);

        assertThat(r.cycle().revenueMinor()).isEqualTo(2_502L);
        assertThat(r.cycle().ticketsSold()).isEqualTo(3);
        // round(100 × (2502 − 3000) / 3000) = round(−16.6) = −17; tickets (3 − 1) / 1 = +200%.
        assertThat(r.cycle().deltas().revenuePct()).isEqualTo(-17);
        assertThat(r.cycle().deltas().ticketsPct()).isEqualTo(200);
    }

    @Test
    void cycle_delta_rounds_a_rise_and_a_fall() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = emptyHome(orgId);
        stubCycle30d(orgId, new Window(4_500, 1), new Window(3_000, 2));

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);

        assertThat(r.cycle().deltas().revenuePct()).isEqualTo(50);
        assertThat(r.cycle().deltas().ticketsPct()).isEqualTo(-50);
    }

    @ParameterizedTest(name = "current {0}/{1} tickets, prior empty")
    @CsvSource({"2502, 3", "0, 0"})
    void cycle_deltas_are_null_when_the_prior_window_is_empty(long currentNet, int currentTickets) {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = emptyHome(orgId);
        stubCycle30d(orgId, new Window(currentNet, currentTickets), new Window(0, 0));

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);

        assertThat(r.cycle().deltas().revenuePct()).isNull();
        assertThat(r.cycle().deltas().ticketsPct()).isNull();
    }

    @Test
    void cycle_all_reads_from_epoch_with_null_deltas() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = emptyHome(orgId);
        when(revenue.forOrgWindow(eq(orgId), eq(Instant.EPOCH), any())).thenReturn(new Window(2_502, 3));

        DashboardResponse r = sut.build(p, DashboardPeriod.ALL, DashboardPeriod.ALL);

        assertThat(r.cycle().revenueMinor()).isEqualTo(2_502L);
        assertThat(r.cycle().ticketsSold()).isEqualTo(3);
        assertThat(r.cycle().deltas().revenuePct()).isNull();
        assertThat(r.cycle().deltas().ticketsPct()).isNull();
        assertThat(r.business().totalRevenueMinor()).isEqualTo(2_502L);
    }

    @Test
    void last_event_avg_ticket_is_net_over_sold_tickets_rounded_half_up() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = emptyHome(orgId);
        Event past = pastEvent(orgId);
        when(revenue.netForEvent(past.getId())).thenReturn(3_851L);
        when(revenue.ticketsForEvent(past.getId())).thenReturn(2L);

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);

        // 3851 / 2 = 1925.5 → 1926.
        assertThat(r.lastEvent().metrics().avgTicketMinor()).isEqualTo(1_926);
    }

    @ParameterizedTest(name = "past event present: {0}")
    @ValueSource(booleans = {true, false})
    void last_event_avg_ticket_is_null_without_a_sold_ticket(boolean hasPastEvent) {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = emptyHome(orgId);
        if (hasPastEvent) {
            Event past = pastEvent(orgId);
            when(revenue.netForEvent(past.getId())).thenReturn(0L);
            when(revenue.ticketsForEvent(past.getId())).thenReturn(0L);
        }

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);

        assertThat(r.lastEvent().event() != null).isEqualTo(hasPastEvent);
        assertThat(r.lastEvent().metrics().avgTicketMinor()).isNull();
    }

    @Test
    void business_reads_net_over_its_own_period_and_the_audience_total() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = emptyHome(orgId);
        when(revenue.forOrgWindow(eq(orgId), any(), any())).thenAnswer(inv -> {
            long days = java.time.Duration.between((Instant) inv.getArgument(1), (Instant) inv.getArgument(2)).toDays();
            return days == 90 ? new Window(9_000, 4) : new Window(0, 0);
        });
        when(memberships.countByOrgId(orgId)).thenReturn(7L);

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);

        assertThat(r.business().totalRevenueMinor()).isEqualTo(9_000L);
        assertThat(r.business().audienceCount()).isEqualTo(7L);
    }

    private Event pastEvent(UUID orgId) {
        Event past = new Event();
        past.setId(UUID.randomUUID()); past.setOrgId(orgId);
        past.setName("Last Night"); past.setSlug("last-night");
        past.setStatus(EventStatus.PAST);
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of(past));
        return past;
    }
}
