package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventOverviewResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.PredictionRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventOverviewServiceTest {

    EventRepository events = mock(EventRepository.class);
    PredictionRepository predictions = mock(PredictionRepository.class);
    TicketTierRepository tiers = mock(TicketTierRepository.class);
    EventOverviewService sut = new EventOverviewService(events, predictions, tiers);

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void overview_populates_capacity_from_ticket_tier_sum() {
        UUID orgId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);

        Event e = new Event();
        e.setId(eventId);
        e.setOrgId(orgId);
        e.setStartsAt(Instant.now().plusSeconds(10L * 24 * 3600));
        e.setSold(42);
        e.setRevenueMinor(105_000L);
        e.setCurrency("EUR");
        when(events.findActive(eventId)).thenReturn(Optional.of(e));
        when(tiers.sumQuantityByEventId(eventId)).thenReturn(250);
        when(predictions.findById(eventId)).thenReturn(Optional.empty());

        EventOverviewResponse r = sut.overview(p, eventId);

        EventOverviewResponse.Metrics m = r.metrics();
        assertThat(m.sold()).isEqualTo(42);
        assertThat(m.capacity()).isEqualTo(250);
        assertThat(m.revenueMinor()).isEqualTo(105_000L);
        assertThat(m.currency()).isEqualTo("EUR");
        assertThat(m.daysOut()).isBetween(9, 10);
    }

    @Test
    void overview_returns_zero_capacity_when_no_tiers_configured() {
        UUID orgId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);

        Event e = new Event();
        e.setId(eventId);
        e.setOrgId(orgId);
        e.setStartsAt(Instant.now().plusSeconds(3600));
        when(events.findActive(eventId)).thenReturn(Optional.of(e));
        when(tiers.sumQuantityByEventId(eventId)).thenReturn(0);
        when(predictions.findById(eventId)).thenReturn(Optional.empty());

        EventOverviewResponse r = sut.overview(p, eventId);

        assertThat(r.metrics().capacity()).isZero();
        assertThat(r.metrics().sold()).isZero();
    }

    @Test
    void overview_404_when_event_belongs_to_other_org() {
        UUID orgId = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);

        Event e = new Event();
        e.setId(eventId);
        e.setOrgId(otherOrg);
        when(events.findActive(eventId)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> sut.overview(p, eventId))
                .isInstanceOf(ApiException.class);
    }
}
