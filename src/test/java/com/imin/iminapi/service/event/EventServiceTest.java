package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.dto.event.TicketTierEmbeddedPatch;
import com.imin.iminapi.dto.event.VenueDto;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.stripe.StripeConnectService;
import com.imin.iminapi.web.IfMatchSupport;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
    TicketTierService tierService = mock(TicketTierService.class);
    StripeConnectService stripeConnect = mock(StripeConnectService.class);

    EventService sut = new EventService(events, tiers, promos, predictions, validator, ifMatch, tierService, stripeConnect);

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
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        EventDto dto = sut.patch(p, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest("New name", null, null, "Techno", null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null));

        assertThat(dto.name()).isEqualTo("New name");
        assertThat(dto.genre()).isEqualTo("Techno");
        assertThat(dto.tiers()).isNotNull();
        assertThat(dto.promoCodes()).isNotNull();
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
    void patch_with_duplicate_slug_throws_DUPLICATE() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("existing-slug");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenThrow(new DataIntegrityViolationException("unique constraint"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                sut.patch(p, e.getId(), "\"" + updated + "\"",
                        new EventPatchRequest(null, "taken-slug", null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null)))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.DUPLICATE);
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
        e.setDescription("d");
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
    void unpublish_live_with_no_sold_tickets_transitions_to_draft() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("Test"); e.setSlug("test");
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.parse("2026-05-01T00:00:00Z"));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        EventDto dto = sut.unpublish(p, e.getId());

        assertThat(dto.status()).isEqualTo("draft");
        assertThat(e.getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void unpublish_blocked_when_any_tier_has_sold_tickets() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setStatus(EventStatus.LIVE);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        TicketTier sold = new TicketTier();
        sold.setEventId(e.getId());
        sold.setName("GA"); sold.setKind(TicketTierKind.STANDARD);
        sold.setPriceMinor(1000); sold.setQuantity(100); sold.setSold(3);
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of(sold));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.unpublish(p, e.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.INVALID_STATE);
        // The event status must not have changed.
        assertThat(e.getStatus()).isEqualTo(EventStatus.LIVE);
        verify(events, never()).save(any(Event.class));
    }

    @Test
    void unpublish_already_draft_throws_INVALID_STATE() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setStatus(EventStatus.DRAFT);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.unpublish(p, e.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.INVALID_STATE);
    }

    @Test
    void unpublish_404_when_event_in_other_org() {
        AuthPrincipal p = principal();
        Event other = new Event();
        other.setId(UUID.randomUUID()); other.setOrgId(UUID.randomUUID());
        other.setStatus(EventStatus.LIVE);
        when(events.findActive(other.getId())).thenReturn(Optional.of(other));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.unpublish(p, other.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.NOT_FOUND);
    }

    @Test
    void patch_invokes_reconcileEmbedded_when_tiers_provided() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName(""); e.setSlug("draft-x");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        TicketTierEmbeddedPatch tp =
                new TicketTierEmbeddedPatch(
                        null, "GA", "general", 1500, 100, null, null, null, null, null, null);

        sut.patch(p, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null,
                        List.of(tp), null));

        verify(tierService).reconcileEmbedded(eq(e), eq(List.of(tp)));
    }

    @Test
    void patch_skips_reconcileEmbedded_when_tiers_null() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName(""); e.setSlug("draft-x");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        sut.patch(p, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest("Renamed", null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null));

        verifyNoInteractions(tierService);
    }

    @Test
    void publish_paid_event_blocked_when_stripe_not_ready() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("Paid"); e.setSlug("paid");
        e.setStartsAt(Instant.parse("2026-06-01T20:00:00Z"));
        e.setEndsAt(Instant.parse("2026-06-02T04:00:00Z"));
        e.setVenueStreet("12 Main"); e.setVenueCity("Berlin"); e.setVenuePostalCode("10115");
        e.setDescription("d");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        TicketTier paid = new TicketTier();
        paid.setEventId(e.getId());
        paid.setName("GA");
        paid.setKind(TicketTierKind.STANDARD);
        paid.setPriceMinor(1500);
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of(paid));

        when(stripeConnect.getStatus(eq(p), eq(p.orgId())))
                .thenReturn(new StripeConnectService.StatusResult(null, false, false, null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.publish(p, e.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.STRIPE_NOT_READY);
        verify(events, never()).save(any(Event.class));
    }

    @Test
    void publish_paid_event_allowed_when_stripe_ready() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("Paid"); e.setSlug("paid");
        e.setStartsAt(Instant.parse("2026-06-01T20:00:00Z"));
        e.setEndsAt(Instant.parse("2026-06-02T04:00:00Z"));
        e.setVenueStreet("12 Main"); e.setVenueCity("Berlin"); e.setVenuePostalCode("10115");
        e.setDescription("d");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        TicketTier paid = new TicketTier();
        paid.setEventId(e.getId());
        paid.setName("GA");
        paid.setKind(TicketTierKind.STANDARD);
        paid.setPriceMinor(1500);
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of(paid));

        when(stripeConnect.getStatus(eq(p), eq(p.orgId())))
                .thenReturn(new StripeConnectService.StatusResult("acct_123", true, true, "verified"));

        EventDto dto = sut.publish(p, e.getId());
        assertThat(dto.status()).isEqualTo("live");
    }

    @Test
    void publish_free_event_skips_stripe_check() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("Free"); e.setSlug("free");
        e.setStartsAt(Instant.parse("2026-06-01T20:00:00Z"));
        e.setEndsAt(Instant.parse("2026-06-02T04:00:00Z"));
        e.setVenueStreet("12 Main"); e.setVenueCity("Berlin"); e.setVenuePostalCode("10115");
        e.setDescription("d");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        TicketTier free = new TicketTier();
        free.setEventId(e.getId());
        free.setName("Free");
        free.setKind(TicketTierKind.STANDARD);
        free.setPriceMinor(0);
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of(free));

        EventDto dto = sut.publish(p, e.getId());
        assertThat(dto.status()).isEqualTo("live");
        verifyNoInteractions(stripeConnect);
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
