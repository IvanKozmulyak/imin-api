package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.dto.event.VenueDto;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.web.IfMatchSupport;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EventServiceTest {

    EventRepository events = mock(EventRepository.class);
    TicketTierRepository tiers = mock(TicketTierRepository.class);
    PromoCodeRepository promos = mock(PromoCodeRepository.class);
    PredictionRepository predictions = mock(PredictionRepository.class);
    IfMatchSupport ifMatch = new IfMatchSupport();
    EventValidator validator = new EventValidator();

    EventService sut = new EventService(events, tiers, promos, predictions, validator, ifMatch);

    private AuthPrincipal principal() {
        return new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void create_draft_with_empty_body_returns_event_in_draft_status() {
        AuthPrincipal p = principal();
        when(events.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        EventDto dto = sut.createDraft(p, new EventPatchRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null));

        assertThat(dto.status()).isEqualTo("draft");
        assertThat(dto.orgId()).isEqualTo(p.orgId());
        assertThat(dto.createdBy()).isEqualTo(p.userId());
        assertThat(dto.slug()).isNotBlank();
    }

    @Test
    void list_returns_org_scoped_paginated_summaries() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        when(events.findVisibleByOrg(eq(p.orgId()), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(e), PageRequest.of(0, 20), 1));

        PageResponse<EventDto> r = sut.list(p, null, 1, 20);
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).id()).isEqualTo(e.getId());
    }

    @Test
    void detail_404_when_event_in_other_org() {
        AuthPrincipal p = principal();
        Event other = new Event();
        other.setId(UUID.randomUUID()); other.setOrgId(UUID.randomUUID());
        when(events.findActive(other.getId())).thenReturn(Optional.of(other));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.detail(p, other.getId()))
                .isInstanceOf(com.imin.iminapi.security.ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void detail_returns_event_with_tiers_promos_and_null_prediction() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        EventDto dto = sut.detail(p, e.getId());
        assertThat(dto.tiers()).isEmpty();
        assertThat(dto.promoCodes()).isEmpty();
        assertThat(dto.prediction()).isNull();
    }

    @Test
    void patch_with_matching_ifMatch_updates_fields() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName(""); e.setSlug("draft-x");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventDto dto = sut.patch(p, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest("New name", null, null, "Techno", null, null, null, null, null,
                        null, null, null, null, 250, null, null, null, null, null, null));

        assertThat(dto.name()).isEqualTo("New name");
        assertThat(dto.genre()).isEqualTo("Techno");
        assertThat(dto.capacity()).isEqualTo(250);
    }

    @Test
    void patch_with_mismatched_ifMatch_throws_STALE_WRITE() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName(""); e.setSlug("draft-x");
        e.setUpdatedAt(Instant.parse("2026-04-23T10:00:00Z"));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                sut.patch(p, e.getId(), "\"2026-01-01T00:00:00Z\"",
                        new EventPatchRequest("X", null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null)))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.STALE_WRITE);
    }

    @Test
    void publish_on_complete_event_transitions_to_live() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("Test"); e.setSlug("test");
        e.setStartsAt(Instant.parse("2026-06-01T20:00:00Z"));
        e.setEndsAt(Instant.parse("2026-06-02T04:00:00Z"));
        e.setVenueStreet("12 Main"); e.setVenueCity("Berlin"); e.setVenuePostalCode("10115");
        e.setDescription("d"); e.setCapacity(100);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        EventDto dto = sut.publish(p, e.getId());
        assertThat(dto.status()).isEqualTo("live");
        assertThat(dto.publishedAt()).isNotNull();
    }

    @Test
    void publish_already_live_throws_INVALID_STATE() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setStatus(EventStatus.LIVE);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.publish(p, e.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.INVALID_STATE);
    }

    @Test
    void publish_incomplete_event_throws_PUBLISH_VALIDATION_FAILED() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName(""); // missing
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.publish(p, e.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.PUBLISH_VALIDATION_FAILED);
    }
}
