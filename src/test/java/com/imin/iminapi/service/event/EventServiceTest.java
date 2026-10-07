package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.dto.event.PromoCodeEmbeddedPatch;
import com.imin.iminapi.dto.event.TicketTierEmbeddedPatch;
import com.imin.iminapi.dto.event.VenueDto;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.stripe.StripeConnectService;
import com.imin.iminapi.web.IfMatchSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
        // Genre keeps the organizer's casing (V82) — it is a display label both frontends
        // print verbatim. Matching is done on the derived genre_key, not on this string.
        assertThat(dto.genre()).isEqualTo("Techno");
        assertThat(dto.tiers()).isNotNull();
        assertThat(dto.promoCodes()).isNotNull();
    }

    /**
     * events-2: the If-Match header reached the service and was thrown away, so two
     * organizer tabs could silently clobber each other. Same guarantee OrgService.patch
     * has already made (409 STALE_WRITE).
     */
    @Test
    void patch_with_mismatched_ifMatch_throws_STALE_WRITE() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        e.setUpdatedAt(Instant.parse("2026-04-23T10:00:00Z"));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                sut.patch(p, e.getId(), "\"2026-01-01T00:00:00Z\"",
                        new EventPatchRequest("New name", null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null)))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.STALE_WRITE);

        // The stale write must not reach the repository at all.
        verify(events, never()).save(any(Event.class));
    }

    /**
     * api-6, the body half: {@code EventVisibility.fromWire} is a bare {@code valueOf}, so
     * {"visibility":"unlisted"} threw IllegalArgumentException out of applyPatch and landed on
     * the global Throwable handler — 500 INTERNAL. EventPatchRequest carries no bean-validation
     * constraint on the field and the controller binds the body without @Valid, so there is no
     * upstream guard either; the enum boundary is the only place this can be caught.
     */
    @Test
    void patch_with_unknown_visibility_throws_FIELD_INVALID_not_IllegalArgument() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                sut.patch(p, e.getId(), "\"" + updated + "\"",
                        new EventPatchRequest(null, null, "unlisted", null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null)))
                .isInstanceOf(com.imin.iminapi.security.ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.FIELD_INVALID);

        verify(events, never()).save(any(Event.class));
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

        when(tiers.existsSoldByEventId(e.getId())).thenReturn(true);

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

    /** A buyer on a hosted Checkout page holds reserved > 0 while sold is still 0; unpublish must refuse. */
    @Test
    void unpublish_blocked_when_a_checkout_is_in_flight() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setStatus(EventStatus.LIVE);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        when(tiers.existsReservedByEventId(e.getId())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.unpublish(p, e.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.INVALID_STATE)
                .hasMessageContaining("checkout");
        verify(events, never()).save(any(Event.class));
    }

    @Test
    void unpublish_locksEveryTierAfterTheEventAndBeforeCounting() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setStatus(EventStatus.LIVE);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        when(tiers.findIdsByEventId(e.getId())).thenReturn(List.of(idB, idA));

        sut.unpublish(p, e.getId());

        org.mockito.InOrder order = inOrder(events, tierService, tiers);
        order.verify(events).lockActiveForWrite(e.getId(), p.orgId());
        order.verify(tierService).lockForWrite(e.getId(), List.of(idB, idA));
        order.verify(tiers).existsSoldByEventId(e.getId());
        order.verify(tiers).existsReservedByEventId(e.getId());
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

    /**
     * events-4: the ticket line item at checkout is the stored Stripe Price (minted in the
     * OLD currency) while the service-fee line item is built inline from event.currency, and
     * Stripe requires one currency per Session. Changing the currency after tiers are synced
     * therefore kills checkout for the event, so it is refused.
     */
    @Test
    void patch_currency_change_throws_INVALID_STATE_when_a_tier_has_a_stripe_price() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x"); e.setCurrency("EUR");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(tiers.existsSyncedStripePrice(e.getId())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                sut.patch(p, e.getId(), "\"" + updated + "\"",
                        new EventPatchRequest(null, null, null, null, null, null, null, null, null,
                                null, null, null, "GBP", null, null, null, null)))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.INVALID_STATE);

        assertThat(e.getCurrency()).isEqualTo("EUR");
        verify(events, never()).save(any(Event.class));
    }

    /** Re-sending the same currency is a no-op, not a conflict — autosave does exactly that. */
    @Test
    void patch_same_currency_is_allowed_even_when_tiers_are_synced() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x"); e.setCurrency("EUR");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.existsSyncedStripePrice(e.getId())).thenReturn(true);
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        sut.patch(p, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest(null, null, null, null, null, null, null, null, null,
                        null, null, null, "eur", null, null, null, null));

        assertThat(e.getCurrency()).isEqualTo("eur");
    }

    // ---- promo whole-list reconcile on a LIVE event (events-10 / events-18) ----

    private Event patchableEvent(AuthPrincipal p, Instant updated) {
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        e.setStatus(EventStatus.LIVE);
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());
        return e;
    }

    private PromoCode redeemedPromo(UUID eventId, String code, int maxUses, int usedCount) {
        PromoCode pc = new PromoCode();
        pc.setId(UUID.randomUUID());
        pc.setEventId(eventId);
        pc.setCode(code);
        pc.setDiscountPct(10);
        pc.setMaxUses(maxUses);
        pc.setUsedCount(usedCount);
        pc.setEnabled(true);
        return pc;
    }

    /**
     * The whole-list replace documents itself as "safe because PATCH only operates on
     * drafts", but patch() never checks status. Lowering maxUses below what has already
     * been redeemed makes the code read as exhausted everywhere, so it is rejected with
     * the same message the per-id path uses.
     */
    @Test
    void patch_promoCodes_rejects_maxUses_below_usedCount() {
        AuthPrincipal p = principal();
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        Event e = patchableEvent(p, updated);
        when(promos.findByEventId(e.getId()))
                .thenReturn(List.of(redeemedPromo(e.getId(), "EARLY", 50, 3)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                sut.patch(p, e.getId(), "\"" + updated + "\"",
                        new EventPatchRequest(null, null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null,
                                List.of(new PromoCodeEmbeddedPatch("EARLY", 10, 1)))))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.INVALID_REQUEST)
                .satisfies(ex -> assertThat(((com.imin.iminapi.security.ApiException) ex).fields())
                        .containsKey("promoCodes[0].maxUses"));

        verify(promos, never()).save(any(PromoCode.class));
    }

    /**
     * orders.promo_code_id is a bare UUID column with no FK, so hard-deleting a redeemed
     * code leaves every order that used it pointing at nothing. Disable instead — the
     * whole-list contract still holds for the draft case the wizard actually uses.
     */
    @Test
    void patch_promoCodes_disables_rather_than_deletes_a_redeemed_code() {
        AuthPrincipal p = principal();
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        Event e = patchableEvent(p, updated);
        PromoCode redeemed = redeemedPromo(e.getId(), "EARLY", 50, 3);
        when(promos.findByEventId(e.getId())).thenReturn(List.of(redeemed));

        sut.patch(p, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, List.of()));

        verify(promos, never()).delete(any(PromoCode.class));
        assertThat(redeemed.isEnabled()).isFalse();
    }

    /** An unredeemed code absent from the list is still removed — the contract is unchanged. */
    @Test
    void patch_promoCodes_still_deletes_an_unredeemed_code() {
        AuthPrincipal p = principal();
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        Event e = patchableEvent(p, updated);
        PromoCode unused = redeemedPromo(e.getId(), "NEVERUSED", 50, 0);
        when(promos.findByEventId(e.getId())).thenReturn(List.of(unused));

        sut.patch(p, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, List.of()));

        verify(promos).delete(unused);
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
    void patch_locksEmbeddedTiersBeforeTheEventFlush() {
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

        UUID existingId = UUID.randomUUID();
        TicketTierEmbeddedPatch update =
                new TicketTierEmbeddedPatch(existingId, "Renamed", null, null, null, null, null, null, null, null);
        TicketTierEmbeddedPatch create =
                new TicketTierEmbeddedPatch(null, "GA", 1500, 100, null, null, null, null, null, null);

        sut.patch(p, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                        List.of(update, create), null));

        var order = inOrder(tierService, events);
        order.verify(tierService).lockForWrite(e.getId(), java.util.Arrays.asList(existingId, null));
        order.verify(events).flush();
        order.verify(tierService).reconcileEmbedded(eq(e), eq(List.of(update, create)));
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

    /** Publish reads only the cached mirror under the lock: not ready refuses, ready goes live. */
    @ParameterizedTest(name = "stripe ready={0}")
    @ValueSource(booleans = {false, true})
    void publish_paid_event_is_gated_on_the_cached_stripe_status(boolean ready) {
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

        when(stripeConnect.getStatusCached(eq(p.orgId()))).thenReturn(ready
                ? new StripeConnectService.StatusResult("acct_123", com.imin.iminapi.stripe.StripeConnectState.ACTIVE,
                        true, true, java.util.List.of(), java.util.List.of(), null)
                : new StripeConnectService.StatusResult(null, com.imin.iminapi.stripe.StripeConnectState.NOT_STARTED,
                        false, false, java.util.List.of(), java.util.List.of(), null));

        if (ready) {
            EventDto dto = sut.publish(p, e.getId());
            assertThat(dto.status()).isEqualTo("live");
        } else {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.publish(p, e.getId()))
                    .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.STRIPE_NOT_READY);
            verify(events, never()).save(any(Event.class));
        }
        verify(stripeConnect, never()).getStatus(any(), any());
    }

    // ---- precheckPublish: lock-free checks before the Stripe refresh ----------------------------

    private Event publishableDraft(AuthPrincipal p) {
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("Ready"); e.setSlug("ready");
        Instant start = Instant.now().plus(java.time.Duration.ofDays(10));
        e.setStartsAt(start);
        e.setEndsAt(start.plus(java.time.Duration.ofHours(8)));
        e.setVenueStreet("12 Main"); e.setVenueCity("Berlin"); e.setVenuePostalCode("10115");
        e.setDescription("d");
        return e;
    }

    private static TicketTier tierPriced(UUID eventId, int priceMinor) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("T" + priceMinor);
        t.setPriceMinor(priceMinor);
        return t;
    }

    @Test
    void precheckPublish_404_for_other_org_without_taking_the_lock() {
        AuthPrincipal p = principal();
        Event other = publishableDraft(p);
        other.setOrgId(UUID.randomUUID());
        when(events.findActive(other.getId())).thenReturn(Optional.of(other));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.precheckPublish(p, other.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.NOT_FOUND);
        verify(events, never()).lockActiveForWrite(any(), any());
        verifyNoInteractions(stripeConnect);
    }

    @Test
    void precheckPublish_live_event_is_409_before_validation() {
        AuthPrincipal p = principal();
        Event e = publishableDraft(p);
        e.setName("");
        e.setStatus(EventStatus.LIVE);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.precheckPublish(p, e.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.INVALID_STATE);
        verify(events, never()).lockActiveForWrite(any(), any());
        verifyNoInteractions(stripeConnect);
    }

    @Test
    void precheckPublish_incomplete_draft_is_422_before_reading_tiers() {
        AuthPrincipal p = principal();
        Event e = publishableDraft(p);
        e.setName("");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.precheckPublish(p, e.getId()))
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.PUBLISH_VALIDATION_FAILED);
        verifyNoInteractions(tiers);
        verify(events, never()).lockActiveForWrite(any(), any());
        verifyNoInteractions(stripeConnect);
    }

    /** True when any tier is paid, so only then does publish refresh Stripe first. */
    @ParameterizedTest(name = "prices={0} -> {1}")
    @CsvSource({"'0,1500', true", "'0', false"})
    void precheckPublish_is_true_only_when_a_tier_is_paid(String prices, boolean expected) {
        AuthPrincipal p = principal();
        Event e = publishableDraft(p);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(java.util.Arrays.stream(prices.split(","))
                .map(price -> tierPriced(e.getId(), Integer.parseInt(price))).toList());

        assertThat(sut.precheckPublish(p, e.getId())).isEqualTo(expected);
        verify(events, never()).lockActiveForWrite(any(), any());
        verifyNoInteractions(stripeConnect);
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

    /**
     * The zone comes from the venue country unless one is sent; "UTC" counts as unset, because the
     * webapp autosave sends timezone:"UTC" on every save (deploy-ordering robustness).
     */
    @ParameterizedTest(name = "sent={0} country={1} -> {2}")
    @CsvSource({
            ",                 FR, Europe/Paris",
            ",                 ,   UTC",
            "America/New_York, FR, America/New_York",
            "UTC,              DE, Europe/Berlin"})
    void create_draft_timezone(String sentTimezone, String venueCountry, String expected) {
        AuthPrincipal p = principal();
        stubSaveEchoWithId();

        EventDto dto = sut.createDraft(p, bodyWith(sentTimezone, venueCountry == null ? null : venue(venueCountry)));

        assertThat(dto.timezone()).isEqualTo(expected);
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

    /**
     * events-11: VenueDto has no required fields and EventPatchRequest's contract is
     * "null = leave unchanged", but venueName and venueCountry were applied
     * unconditionally while street/city/postalCode were null-guarded. A partial venue
     * patch therefore silently erased the name and the country — and losing the country
     * also moves venueAddressKey, which fires a spurious re-geocode.
     */
    @Test
    void patch_partial_venue_leaves_unsent_name_and_country_alone() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        e.setVenueName("Le Club"); e.setVenueCountry("FR");
        e.setVenueStreet("1 Rue"); e.setVenueCity("Paris"); e.setVenuePostalCode("75001");
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        sut.patch(p, e.getId(), "\"" + updated + "\"",
                bodyWith(null, new VenueDto(null, "2 Rue X", null, null, null)));

        assertThat(e.getVenueStreet()).isEqualTo("2 Rue X");
        assertThat(e.getVenueName()).isEqualTo("Le Club");
        assertThat(e.getVenueCountry()).isEqualTo("FR");
        assertThat(e.getVenueCity()).isEqualTo("Paris");
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

    enum AddressWrite { CREATE_WITH_ADDRESS, CREATE_WITHOUT_ADDRESS, PATCH_MOVES_VENUE, PATCH_RETYPES_SAME_ADDRESS }

    /**
     * Only a new or moved address asks for a geocode. Autosave resends the whole form on every keystroke
     * pause, and re-geocoding an unchanged address would exhaust the provider's rate budget for nothing.
     */
    @ParameterizedTest
    @CsvSource({"CREATE_WITH_ADDRESS, true", "CREATE_WITHOUT_ADDRESS, false",
            "PATCH_MOVES_VENUE, true", "PATCH_RETYPES_SAME_ADDRESS, false"})
    void address_write_asks_for_a_geocode_only_when_the_address_changed(AddressWrite write, boolean asks) {
        AuthPrincipal p = principal();
        stubSaveEchoWithId();
        var pub = mock(org.springframework.context.ApplicationEventPublisher.class);
        EventService service = serviceWithPublisher(pub);

        switch (write) {
            case CREATE_WITH_ADDRESS -> service.createDraft(p, bodyWith(null, venue("FR")));
            case CREATE_WITHOUT_ADDRESS -> service.createDraft(p, bodyWith(null, null));
            case PATCH_MOVES_VENUE -> service.patch(p, venueInParis(p), null, bodyWith(null,
                    new VenueDto("Le Club", "2 Rue", "Metz", "57000", "FR")));
            // Same address, different casing/whitespace and a changed name (name is not an address).
            case PATCH_RETYPES_SAME_ADDRESS -> service.patch(p, venueInParis(p), null, bodyWith(null,
                    new VenueDto("Renamed Club", " 1 Rue ", "paris", "75001", "FR")));
        }

        verify(pub, times(asks ? 1 : 0)).publishEvent(any(VenueAddressChangedEvent.class));
    }

    private UUID venueInParis(AuthPrincipal p) {
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
        return e.getId();
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

    /**
     * City and genre: whitespace tidied, case left exactly as typed — case-folding city names destroys
     * real ones ('s-Hertogenbosch, L'Aquila). Country is upper-cased.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "create|'  Techno   Classics '|'  Le   Mans '|' fr '|Le Mans|FR|Techno Classics",
            "patch |Techno               |' METZ '      |fr    |METZ   |FR|Techno"})
    void write_normalises_city_country_and_genre(String op, String genre, String city, String country,
                                                 String expectedCity, String expectedCountry, String expectedGenre) {
        AuthPrincipal p = principal();
        EventPatchRequest body = facetBody(genre, new VenueDto("Le Club", "1 Rue", city, "57000", country));
        Event saved;
        if (op.equals("create")) {
            saved = captureCreated(p, body);
        } else {
            saved = new Event();
            saved.setId(UUID.randomUUID()); saved.setOrgId(p.orgId()); saved.setSlug("x");
            when(events.findActive(saved.getId())).thenReturn(Optional.of(saved));
            when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
            when(tiers.findByEventIdOrderBySortOrderAsc(any())).thenReturn(List.of());
            when(promos.findByEventId(any())).thenReturn(List.of());
            when(predictions.findById(any())).thenReturn(Optional.empty());
            sut.patch(p, saved.getId(), null, body);
        }

        assertThat(saved.getVenueCity()).isEqualTo(expectedCity);
        assertThat(saved.getVenueCountry()).isEqualTo(expectedCountry);
        assertThat(saved.getGenre()).isEqualTo(expectedGenre);
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
