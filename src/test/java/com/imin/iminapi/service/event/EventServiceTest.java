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
                null, null, null, null, null, null, null, null));

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
                        null, null, null, null, null, null, null, null));

        assertThat(dto.name()).isEqualTo("New name");
        // Genre is canonicalised to lower case on write (V82) — it is a facet token that
        // `?genre=` matches exactly, not a display label.
        assertThat(dto.genre()).isEqualTo("techno");
        assertThat(dto.tiers()).isNotNull();
        assertThat(dto.promoCodes()).isNotNull();
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
                                null, null, null, null, null, null, null, null)))
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
        sold.setName("GA");
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
                        null, "GA", 1500, 100, null, null, null, null, null, null);

        sut.patch(p, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null,
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
                        null, null, null, null, null, null, null, null));

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
        paid.setPriceMinor(1500);
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of(paid));

        when(stripeConnect.getStatus(eq(p), eq(p.orgId())))
                .thenReturn(new StripeConnectService.StatusResult(null,
                        com.imin.iminapi.stripe.StripeConnectState.NOT_STARTED,
                        false, false, java.util.List.of(), java.util.List.of(), null));

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
        paid.setPriceMinor(1500);
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of(paid));

        when(stripeConnect.getStatus(eq(p), eq(p.orgId())))
                .thenReturn(new StripeConnectService.StatusResult("acct_123",
                        com.imin.iminapi.stripe.StripeConnectState.ACTIVE,
                        true, true, java.util.List.of(), java.util.List.of(), null));

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
        free.setPriceMinor(0);
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of(free));

        EventDto dto = sut.publish(p, e.getId());
        assertThat(dto.status()).isEqualTo("live");
        verifyNoInteractions(stripeConnect);
    }

    // ---- Timezone derivation (bug 86ca74h6c) --------------------------------------------------

    private void stubSaveEchoWithId() {
        when(events.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    private EventPatchRequest bodyWith(String timezone, VenueDto venue) {
        return new EventPatchRequest(null, null, null, null, null, null, null, timezone, venue,
                null, null, null, null, null, null, null, null);
    }

    private static VenueDto venue(String country) {
        return new VenueDto("Le Club", "1 Rue", "Paris", "75001", country);
    }

    @Test
    void create_draft_derives_timezone_from_venue_country() {
        AuthPrincipal p = principal();
        stubSaveEchoWithId();

        EventDto dto = sut.createDraft(p, bodyWith(null, venue("FR")));

        assertThat(dto.timezone()).isEqualTo("Europe/Paris");
    }

    @Test
    void create_draft_without_venue_keeps_utc_default() {
        AuthPrincipal p = principal();
        stubSaveEchoWithId();

        EventDto dto = sut.createDraft(p, bodyWith(null, null));

        assertThat(dto.timezone()).isEqualTo("UTC");
    }

    @Test
    void explicit_timezone_wins_over_venue_country() {
        AuthPrincipal p = principal();
        stubSaveEchoWithId();

        EventDto dto = sut.createDraft(p, bodyWith("America/New_York", venue("FR")));

        assertThat(dto.timezone()).isEqualTo("America/New_York");
    }

    @Test
    void sent_utc_is_treated_as_unset_and_derived_from_country() {
        // The current webapp autosave sends timezone:"UTC" on every save; the server must still
        // derive the real zone from the venue country (deploy-ordering robustness).
        AuthPrincipal p = principal();
        stubSaveEchoWithId();

        EventDto dto = sut.createDraft(p, bodyWith("UTC", venue("DE")));

        assertThat(dto.timezone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void invalid_timezone_rejected_with_400() {
        AuthPrincipal p = principal();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sut.createDraft(p, bodyWith("Not/AZone", venue("FR"))))
                .isInstanceOf(com.imin.iminapi.security.ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.INVALID_REQUEST)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.BAD_REQUEST);
        verify(events, never()).save(any(Event.class));
    }

    @Test
    void patch_derives_timezone_when_zone_still_default_and_country_set() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        assertThat(e.getTimezone()).isEqualTo("UTC"); // entity default
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        EventDto dto = sut.patch(p, e.getId(), "\"" + updated + "\"", bodyWith(null, venue("NL")));

        assertThat(dto.timezone()).isEqualTo("Europe/Amsterdam");
    }

    @Test
    void patch_does_not_overwrite_previously_set_zone_on_later_venue_change() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        e.setTimezone("America/New_York"); // an explicit, non-default choice already stored
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        // Venue moves to FR, but the request carries no explicit timezone — the prior zone stays.
        EventDto dto = sut.patch(p, e.getId(), "\"" + updated + "\"", bodyWith(null, venue("FR")));

        assertThat(dto.timezone()).isEqualTo("America/New_York");
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

    // ---- Venue geocoding trigger (V80) ---------------------------------------------------------

    /**
     * A publisher-wired EventService — the 8-arg constructor used elsewhere in this class
     * leaves the publisher null, which is fine for tests that don't care about events.
     */
    private EventService serviceWithPublisher(org.springframework.context.ApplicationEventPublisher pub) {
        return new EventService(events, tiers, promos, predictions, validator, ifMatch, tierService,
                stripeConnect, null, null, null, pub);
    }

    @Test
    void create_draft_with_an_address_asks_for_a_geocode() {
        AuthPrincipal p = principal();
        stubSaveEchoWithId();
        var pub = mock(org.springframework.context.ApplicationEventPublisher.class);

        serviceWithPublisher(pub).createDraft(p, bodyWith(null, venue("FR")));

        verify(pub).publishEvent(any(VenueAddressChangedEvent.class));
    }

    @Test
    void create_draft_without_an_address_asks_for_nothing() {
        AuthPrincipal p = principal();
        stubSaveEchoWithId();
        var pub = mock(org.springframework.context.ApplicationEventPublisher.class);

        serviceWithPublisher(pub).createDraft(p, bodyWith(null, null));

        verify(pub, never()).publishEvent(any(VenueAddressChangedEvent.class));
    }

    @Test
    void patch_that_moves_the_venue_asks_for_a_geocode() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(p.orgId());
        e.setVenueStreet("1 Rue");
        e.setVenueCity("Paris");
        e.setVenuePostalCode("75001");
        e.setVenueCountry("FR");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(tiers.findByEventIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(promos.findByEventId(any())).thenReturn(List.of());
        when(predictions.findById(any())).thenReturn(Optional.empty());
        stubSaveEchoWithId();
        var pub = mock(org.springframework.context.ApplicationEventPublisher.class);

        serviceWithPublisher(pub).patch(p, e.getId(), null, bodyWith(null,
                new VenueDto("Le Club", "2 Rue", "Metz", "57000", "FR")));

        verify(pub).publishEvent(any(VenueAddressChangedEvent.class));
    }

    @Test
    void patch_that_only_retypes_the_same_address_does_not_burn_a_geocode_call() {
        // Autosave resends the whole form on every keystroke pause. Re-geocoding an
        // unchanged address would exhaust the provider's rate budget for nothing.
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(p.orgId());
        e.setVenueStreet("1 Rue");
        e.setVenueCity("Paris");
        e.setVenuePostalCode("75001");
        e.setVenueCountry("FR");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(tiers.findByEventIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(promos.findByEventId(any())).thenReturn(List.of());
        when(predictions.findById(any())).thenReturn(Optional.empty());
        stubSaveEchoWithId();
        var pub = mock(org.springframework.context.ApplicationEventPublisher.class);

        // Same address, different casing/whitespace and a changed name (name is not an address).
        serviceWithPublisher(pub).patch(p, e.getId(), null, bodyWith(null,
                new VenueDto("Renamed Club", " 1 Rue ", "paris", "75001", "FR")));

        verify(pub, never()).publishEvent(any(VenueAddressChangedEvent.class));
    }

    // ---- Facet normalisation on write (V82) ---------------------------------------------------

    private EventPatchRequest facetBody(String genre, VenueDto venue) {
        return new EventPatchRequest(null, null, null, genre, null, null, null, null, venue,
                null, null, null, null, null, null, null, null);
    }

    private Event captureCreated(AuthPrincipal p, EventPatchRequest body) {
        stubSaveEchoWithId();
        sut.createDraft(p, body);
        var captor = org.mockito.ArgumentCaptor.forClass(Event.class);
        verify(events).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void create_normalises_city_country_and_genre() {
        Event saved = captureCreated(principal(), facetBody("  Techno   Classics ",
                new VenueDto("Le Club", "1 Rue", "  Le   Mans ", "72000", " fr ")));

        // City: whitespace tidied, case left exactly as typed — case-folding city names
        // destroys real ones ('s-Hertogenbosch, L'Aquila).
        assertThat(saved.getVenueCity()).isEqualTo("Le Mans");
        assertThat(saved.getVenueCountry()).isEqualTo("FR");
        assertThat(saved.getGenre()).isEqualTo("techno classics");
    }

    @Test
    void patch_normalises_city_country_and_genre() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId()); e.setSlug("x");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(promos.findByEventId(any())).thenReturn(List.of());
        when(predictions.findById(any())).thenReturn(Optional.empty());

        sut.patch(p, e.getId(), null, facetBody("Techno",
                new VenueDto("Le Club", "1 Rue", " METZ ", "57000", "fr")));

        assertThat(e.getVenueCity()).isEqualTo("METZ");
        assertThat(e.getVenueCountry()).isEqualTo("FR");
        assertThat(e.getGenre()).isEqualTo("techno");
    }

    @Test
    void blank_country_is_stored_as_null_never_as_an_empty_string() {
        // '' and NULL are the same fact — "we don't know the country" — but SQL GROUP BY
        // treats them as two, which is exactly what split one Metz into three city chips.
        Event fromBlank = captureCreated(principal(), facetBody(null,
                new VenueDto("Le Club", "1 Rue", "Metz", "57000", "   ")));
        assertThat(fromBlank.getVenueCountry()).isNull();
    }

    @Test
    void a_country_that_is_not_two_letters_is_rejected_not_silently_dropped() {
        // venue_country is varchar(2): this used to be a 500 from the driver. Nulling it out
        // instead would quietly lose what the organizer typed, so it is a field error.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sut.createDraft(principal(), facetBody(null,
                                new VenueDto("Le Club", "1 Rue", "Metz", "57000", "France"))))
                .isInstanceOf(com.imin.iminapi.security.ApiException.class)
                .satisfies(ex -> {
                    var api = (com.imin.iminapi.security.ApiException) ex;
                    assertThat(api.status()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    assertThat(api.fields()).containsKey("venue.country");
                });
        verify(events, never()).save(any(Event.class));
    }

}
