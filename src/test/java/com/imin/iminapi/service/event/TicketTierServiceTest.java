package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.TicketTierCreateRequest;
import com.imin.iminapi.dto.event.TicketTierDto;
import com.imin.iminapi.dto.event.TicketTierEmbeddedPatch;
import com.imin.iminapi.dto.event.TicketTierPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.TicketTierKind;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketTierServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant FUTURE = NOW.plusSeconds(3600);

    EventRepository events = mock(EventRepository.class);
    TicketTierRepository tiers = mock(TicketTierRepository.class);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    TicketTierValidator validator = new TicketTierValidator(clock);

    TicketTierService sut = new TicketTierService(tiers, events, validator, clock);

    private final UUID orgId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private final UUID otherOrgId = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private final UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000040");

    private AuthPrincipal principal;
    private Event event;

    @BeforeEach
    void setUp() {
        principal = new AuthPrincipal(userId, orgId, UserRole.OWNER, UUID.randomUUID());

        event = new Event();
        event.setId(eventId);
        event.setOrgId(orgId);
        event.setName("Test Event");
        event.setSlug("test-event");
        event.setUpdatedAt(Instant.parse("2026-04-23T10:00:00Z"));

        when(events.findActive(eventId)).thenReturn(Optional.of(event));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.save(any(TicketTier.class))).thenAnswer(inv -> {
            TicketTier t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        when(tiers.findByEventIdOrderBySortOrderAsc(eventId)).thenReturn(List.of());
    }

    private TicketTier existingTier(UUID id, int sold, int sortOrder) {
        TicketTier t = new TicketTier();
        t.setId(id);
        t.setEventId(eventId);
        t.setName("GA");
        t.setKind(TicketTierKind.STANDARD);
        t.setPriceMinor(1000);
        t.setQuantity(100);
        t.setSold(sold);
        t.setSortOrder(sortOrder);
        t.setEnabled(true);
        return t;
    }

    private TicketTierCreateRequest validCreate() {
        return new TicketTierCreateRequest("GA", "standard", 1000, 100, null, null, null);
    }

    // ── create ─────────────────────────────────────────────────────────────────

    @Test
    void create_persists_tier_and_returns_dto() {
        TicketTierDto dto = sut.create(principal, eventId, validCreate());

        ArgumentCaptor<TicketTier> captor = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(captor.capture());
        TicketTier saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getName()).isEqualTo("GA");
        assertThat(saved.getKind()).isEqualTo(TicketTierKind.STANDARD);
        assertThat(saved.getPriceMinor()).isEqualTo(1000);
        assertThat(saved.getQuantity()).isEqualTo(100);
        assertThat(saved.isEnabled()).isTrue();

        assertThat(dto.eventId()).isEqualTo(eventId);
        assertThat(dto.kind()).isEqualTo("standard");
        assertThat(dto.sold()).isZero();
    }

    @Test
    void create_appliesNextSortOrder_whenOmitted() {
        TicketTier a = existingTier(UUID.randomUUID(), 0, 0);
        TicketTier b = existingTier(UUID.randomUUID(), 0, 5);
        when(tiers.findByEventIdOrderBySortOrderAsc(eventId)).thenReturn(List.of(a, b));

        sut.create(principal, eventId, validCreate());

        ArgumentCaptor<TicketTier> captor = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(captor.capture());
        assertThat(captor.getValue().getSortOrder()).isEqualTo(6);
    }

    @Test
    void create_rejects_when_event_in_other_org_with_404() {
        Event other = new Event();
        other.setId(eventId);
        other.setOrgId(otherOrgId);
        when(events.findActive(eventId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> sut.create(principal, eventId, validCreate()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ex.status().value()).isEqualTo(404);
                });
        verify(tiers, never()).save(any(TicketTier.class));
    }

    @Test
    void create_rejects_invalid_request_with_400() {
        TicketTierCreateRequest bad = new TicketTierCreateRequest(
                null, "standard", 1000, 100, null, null, null);

        assertThatThrownBy(() -> sut.create(principal, eventId, bad))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ex.status().value()).isEqualTo(400);
                    assertThat(ex.fields()).containsKey("name");
                });
        verify(tiers, never()).save(any(TicketTier.class));
    }

    // ── patch ──────────────────────────────────────────────────────────────────

    @Test
    void patch_updates_fields() {
        UUID tierId = UUID.randomUUID();
        TicketTier tier = existingTier(tierId, 0, 0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(tier));

        TicketTierPatchRequest req = new TicketTierPatchRequest(
                "VIP", "earlyBird", 500, 50, null, null, 3, false);

        TicketTierDto dto = sut.patch(principal, eventId, tierId, req);

        assertThat(dto.name()).isEqualTo("VIP");
        assertThat(dto.kind()).isEqualTo("earlyBird");
        assertThat(dto.priceMinor()).isEqualTo(500);
        assertThat(dto.quantity()).isEqualTo(50);
        assertThat(dto.sortOrder()).isEqualTo(3);
        assertThat(dto.enabled()).isFalse();
    }

    @Test
    void patch_with_clearSaleClosesAt_setsNull() {
        UUID tierId = UUID.randomUUID();
        TicketTier tier = existingTier(tierId, 0, 0);
        tier.setSaleClosesAt(FUTURE);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(tier));

        TicketTierPatchRequest req = new TicketTierPatchRequest(
                null, null, null, null, null, true, null, null);

        TicketTierDto dto = sut.patch(principal, eventId, tierId, req);
        assertThat(dto.saleClosesAt()).isNull();
    }

    @Test
    void patch_allows_name_change_when_sold() {
        UUID tierId = UUID.randomUUID();
        TicketTier tier = existingTier(tierId, 5, 0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(tier));

        TicketTierPatchRequest req = new TicketTierPatchRequest(
                "Renamed", null, null, null, null, null, null, null);

        TicketTierDto dto = sut.patch(principal, eventId, tierId, req);
        assertThat(dto.name()).isEqualTo("Renamed");
    }

    @Test
    void patch_returns_404_when_tier_not_under_event() {
        UUID tierId = UUID.randomUUID();
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.empty());

        TicketTierPatchRequest req = new TicketTierPatchRequest(
                "Renamed", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> sut.patch(principal, eventId, tierId, req))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    // Don't leak tier existence — message says "Event" not "TicketTier"
                    assertThat(ex.getMessage()).contains("Event");
                });
    }

    @Test
    void patch_returns_404_when_event_in_other_org() {
        Event other = new Event();
        other.setId(eventId);
        other.setOrgId(otherOrgId);
        when(events.findActive(eventId)).thenReturn(Optional.of(other));
        UUID tierId = UUID.randomUUID();

        TicketTierPatchRequest req = new TicketTierPatchRequest(
                "Renamed", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> sut.patch(principal, eventId, tierId, req))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Test
    void delete_removes_tier() {
        UUID tierId = UUID.randomUUID();
        TicketTier tier = existingTier(tierId, 0, 0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(tier));

        sut.delete(principal, eventId, tierId);

        verify(tiers).delete(tier);
    }

    @Test
    void delete_returns_404_when_tier_not_found() {
        UUID tierId = UUID.randomUUID();
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.delete(principal, eventId, tierId))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // ── reconcileEmbedded ──────────────────────────────────────────────────────

    @Test
    void reconcileEmbedded_creates_when_id_null() {
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                null, "VIP", "standard", 2000, 50, null, null, null, null);

        sut.reconcileEmbedded(event, List.of(p));

        ArgumentCaptor<TicketTier> captor = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(captor.capture());
        TicketTier saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("VIP");
        assertThat(saved.getKind()).isEqualTo(TicketTierKind.STANDARD);
        assertThat(saved.getPriceMinor()).isEqualTo(2000);
        assertThat(saved.getQuantity()).isEqualTo(50);
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void reconcileEmbedded_updates_when_id_matches() {
        UUID tierId = UUID.randomUUID();
        TicketTier existing = existingTier(tierId, 0, 0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(existing));

        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                tierId, "Updated", null, null, null, null, null, null, null);

        sut.reconcileEmbedded(event, List.of(p));

        ArgumentCaptor<TicketTier> captor = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Updated");
        // unchanged fields preserved
        assertThat(captor.getValue().getQuantity()).isEqualTo(100);
    }

    @Test
    void reconcileEmbedded_leaves_unmentioned_tier_alone() {
        UUID mentioned = UUID.randomUUID();
        TicketTier existing = existingTier(mentioned, 0, 0);
        when(tiers.findByIdAndEventId(mentioned, eventId)).thenReturn(Optional.of(existing));

        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                mentioned, "Updated", null, null, null, null, null, null, null);

        sut.reconcileEmbedded(event, List.of(p));

        // Only one save call: for the mentioned tier. No other tiers were fetched or written.
        verify(tiers, times(1)).save(any(TicketTier.class));
        verify(tiers, never()).delete(any(TicketTier.class));
    }

    @Test
    void reconcileEmbedded_rejects_id_pointing_to_other_event_with_400() {
        UUID strayTierId = UUID.randomUUID();
        when(tiers.findByIdAndEventId(strayTierId, eventId)).thenReturn(Optional.empty());

        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                strayTierId, "Updated", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> sut.reconcileEmbedded(event, List.of(p)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ex.status().value()).isEqualTo(400);
                });
    }

    @Test
    void reconcileEmbedded_does_nothing_when_patches_is_null() {
        sut.reconcileEmbedded(event, null);
        verify(tiers, never()).save(any(TicketTier.class));
        verify(tiers, never()).delete(any(TicketTier.class));
    }

    // ── side effects ───────────────────────────────────────────────────────────

    @Test
    void bumpEventUpdatedAt_advances_event_updatedAt() {
        Instant originalUpdated = event.getUpdatedAt();
        sut.create(principal, eventId, validCreate());

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(events).save(captor.capture());
        Event savedEvent = captor.getValue();
        assertThat(savedEvent.getUpdatedAt()).isEqualTo(NOW);
        assertThat(savedEvent.getUpdatedAt()).isNotEqualTo(originalUpdated);
    }
}
