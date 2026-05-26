package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
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
    TicketTierRepository tiers = mock(TicketTierRepository.class);
    UserRepository users = mock(UserRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    AuditLogRepository auditLogs = mock(AuditLogRepository.class);
    DashboardService sut = new DashboardService(events, tiers, users, orders, auditLogs);

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private void stubEmptyAuxiliary(UUID orgId) {
        when(orders.sumRevenueAndCountByOrgInWindow(eq(orgId), any(), any()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{0L, 0L}));
        when(orders.orderCountsByEmailSince(eq(orgId), any())).thenReturn(List.of());
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
        assertThat(r.business().repeatRatePct()).isZero();
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
        when(tiers.sumQuantityByEventId(next.getId())).thenReturn(100);
        when(tiers.sumSoldByEventId(next.getId())).thenReturn(57);
        when(orders.sumTotalMinorByEventId(next.getId())).thenReturn(0L);

        Event past = new Event();
        past.setId(UUID.randomUUID()); past.setOrgId(orgId);
        past.setName("Last Night"); past.setSlug("last-night");
        past.setStatus(EventStatus.PAST);
        past.setEndsAt(Instant.now().minusSeconds(7L * 24 * 3600));
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of(past));
        when(tiers.sumQuantityByEventId(past.getId())).thenReturn(200);
        when(tiers.sumSoldByEventId(past.getId())).thenReturn(198);
        when(orders.sumTotalMinorByEventId(past.getId())).thenReturn(475_200L);

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
    }

    @Test
    void cycle_30d_uses_window_aggregates_with_prior_window_delta() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        User u = new User(); u.setId(p.userId()); u.setFirstName("Jaune"); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of());
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of());
        when(events.countLive(orgId)).thenReturn(2L);
        when(events.countPublished(orgId)).thenReturn(3L);
        when(events.countPast(orgId)).thenReturn(1L);
        Page<com.imin.iminapi.model.AuditLog> emptyPage = new PageImpl<>(List.of());
        when(auditLogs.findByOrgIdOrderByOccurredAtDesc(eq(orgId), any())).thenReturn(emptyPage);

        // Current 30d: 120,000 minor / 30 orders. Prior 30d: 100,000 / 20 → +20% rev, +50% tickets.
        when(orders.sumRevenueAndCountByOrgInWindow(eq(orgId), any(), any()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{120_000L, 30L}))
                .thenReturn(java.util.Collections.singletonList(new Object[]{100_000L, 20L}));
        when(orders.orderCountsByEmailSince(eq(orgId), any())).thenReturn(List.of());

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);
        assertThat(r.cycle().revenueMinor()).isEqualTo(120_000L);
        assertThat(r.cycle().ticketsSold()).isEqualTo(30);
        assertThat(r.cycle().deltas().revenuePct()).isEqualTo(20);
        assertThat(r.cycle().deltas().ticketsPct()).isEqualTo(50);
    }

    @Test
    void business_repeat_rate_counts_buyers_with_more_than_one_order() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        User u = new User(); u.setId(p.userId()); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of());
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of());
        when(events.countLive(orgId)).thenReturn(0L);
        when(events.countPublished(orgId)).thenReturn(0L);
        when(events.countPast(orgId)).thenReturn(0L);
        when(orders.sumRevenueAndCountByOrgInWindow(eq(orgId), any(), any()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{0L, 0L}));
        Page<com.imin.iminapi.model.AuditLog> emptyPage = new PageImpl<>(List.of());
        when(auditLogs.findByOrgIdOrderByOccurredAtDesc(eq(orgId), any())).thenReturn(emptyPage);

        // 4 buyers, 2 of them returned → 50%.
        when(orders.orderCountsByEmailSince(eq(orgId), any())).thenReturn(List.of(
                new Object[]{"a@x.com", 1L},
                new Object[]{"b@x.com", 2L},
                new Object[]{"c@x.com", 3L},
                new Object[]{"d@x.com", 1L}
        ));

        DashboardResponse r = sut.build(p, DashboardPeriod.D30, DashboardPeriod.D90);
        assertThat(r.business().repeatRatePct()).isEqualTo(50);
    }
}
