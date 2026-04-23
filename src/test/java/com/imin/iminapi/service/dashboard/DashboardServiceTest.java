package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dto.UserDto;
import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

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
    DashboardService sut = new DashboardService(events, users);

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void empty_org_returns_null_next_and_zeroed_metrics() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        User u = new User();
        u.setId(p.userId()); u.setName("Jaune"); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of());
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of());
        when(events.countLive(orgId)).thenReturn(0L);
        when(events.countPublished(orgId)).thenReturn(0L);
        when(events.countPast(orgId)).thenReturn(0L);
        java.util.List<Object[]> zeroSums = java.util.Collections.singletonList(new Object[]{0L, 0L});
        when(events.sumRevenueAndSold(orgId)).thenReturn(zeroSums);

        DashboardResponse r = sut.build(p);
        assertThat(r.greeting().name()).isEqualTo("Jaune");
        assertThat(r.now().nextEvent()).isNull();
        assertThat(r.cycle().activeEvents()).isZero();
        assertThat(r.lastEvent().event()).isNull();
        assertThat(r.business().totalRevenueMinor()).isZero();
        assertThat(r.activity()).isEmpty();
        assertThat(r.prediction()).isNull();
    }

    @Test
    void populated_org_returns_next_and_last_with_pct_and_daysOut() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        User u = new User(); u.setId(p.userId()); u.setName("Jaune"); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));

        Event next = new Event();
        next.setId(UUID.randomUUID()); next.setOrgId(orgId);
        next.setName("Next Night"); next.setSlug("next-night");
        next.setStartsAt(Instant.now().plusSeconds(28L * 24 * 3600));
        next.setCapacity(100); next.setSold(57);
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of(next));

        Event past = new Event();
        past.setId(UUID.randomUUID()); past.setOrgId(orgId);
        past.setName("Last Night"); past.setSlug("last-night");
        past.setStatus(EventStatus.PAST);
        past.setEndsAt(Instant.now().minusSeconds(7L * 24 * 3600));
        past.setCapacity(200); past.setSold(198); past.setRevenueMinor(475_200);
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of(past));

        when(events.countLive(orgId)).thenReturn(3L);
        when(events.countPublished(orgId)).thenReturn(6L);
        when(events.countPast(orgId)).thenReturn(4L);
        java.util.List<Object[]> popSums = java.util.Collections.singletonList(new Object[]{1_420_800L, 212L});
        when(events.sumRevenueAndSold(orgId)).thenReturn(popSums);

        DashboardResponse r = sut.build(p);
        assertThat(r.now().nextEvent().id()).isEqualTo(next.getId());
        assertThat(r.now().pct()).isEqualTo(57);
        assertThat(r.now().daysOut()).isBetween(27, 28);
        assertThat(r.cycle().activeEvents()).isEqualTo(3);
        assertThat(r.lastEvent().event().id()).isEqualTo(past.getId());
        assertThat(r.lastEvent().metrics().attended()).isEqualTo(198);
        assertThat(r.lastEvent().metrics().avgTicketMinor()).isEqualTo(2400);
        assertThat(r.business().totalRevenueMinor()).isEqualTo(1_420_800L);
        assertThat(r.business().eventsPublished()).isEqualTo(6);
    }
}
