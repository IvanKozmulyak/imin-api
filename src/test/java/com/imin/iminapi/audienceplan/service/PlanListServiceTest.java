package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.dto.AudiencePlanListItem;
import com.imin.iminapi.audienceplan.model.AudiencePlan;
import com.imin.iminapi.audienceplan.repository.AudiencePlanRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanListServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    void from_blankOrMissing_isNow() {
        assertThat(PlanListService.from(null, NOW)).isEqualTo(NOW);
        assertThat(PlanListService.from("  ", NOW)).isEqualTo(NOW);
    }

    @Test
    void from_inThePast_isRaisedToNow_andInTheFuture_isKept() {
        assertThat(PlanListService.from("2026-09-01T00:00:00Z", NOW)).isEqualTo(NOW);
        assertThat(PlanListService.from(" 2026-10-01T00:00:00Z ", NOW)).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void from_unparseable_is400OnTheFromField() {
        assertThatThrownBy(() -> PlanListService.from("2026-10-01", NOW))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.fields()).containsKey("from");
                });
    }

    @Test
    void list_readsThePortraitOncePerGenreAndCity_andTheMailableCountOnce() {
        UUID org = UUID.randomUUID();
        EventRepository events = mock(EventRepository.class);
        AudiencePlanRepository plans = mock(AudiencePlanRepository.class);
        CandidateLoader candidates = mock(CandidateLoader.class);
        PlanService planService = mock(PlanService.class);
        PlanListService service = new PlanListService(mock(AudiencePlanAccess.class), events,
                mock(TicketTierRepository.class), plans, candidates, planService, Clock.fixed(NOW, ZoneOffset.UTC));
        Event first = event(org, 20);
        Event second = event(org, 28);
        when(events.findUpcomingForPlans(eq(org), any(), any())).thenReturn(List.of(first, second));
        when(plans.findCurrentForEvents(eq(org), any())).thenReturn(List.of(plan(first), plan(second)));
        when(candidates.mailableCount(org)).thenReturn(345);
        when(planService.newPeople(any())).thenReturn(List.of());
        when(planService.isFresh(any(), any(), anyList(), anyInt(), anyList())).thenReturn(true);

        assertThat(service.list(org, null)).extracting(AudiencePlanListItem::status).containsExactly("fresh", "fresh");

        verify(planService, times(1)).newPeople(any());
        verify(candidates, times(1)).mailableCount(org);
    }

    /** A live house night in Metz {@code days} out. */
    private static Event event(UUID org, int days) {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(org);
        e.setStatus(EventStatus.LIVE);
        e.setGenre("House & Techno");
        e.setGenreKey("house & techno");
        e.setVenueCity("Metz");
        e.setVenueCityKey("metz");
        e.setStartsAt(NOW.plusSeconds(days * 86_400L));
        return e;
    }

    private static AudiencePlan plan(Event e) {
        AudiencePlan p = new AudiencePlan();
        p.setId(UUID.randomUUID());
        p.setEventId(e.getId());
        p.setCreatedAt(NOW);
        return p;
    }
}
