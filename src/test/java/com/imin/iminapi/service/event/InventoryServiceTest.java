package com.imin.iminapi.service.event;

import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.TicketTierKind;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryServiceTest {

    private TicketTierRepository tiers;
    private InventoryService svc;

    @BeforeEach
    void setUp() {
        tiers = mock(TicketTierRepository.class);
        svc = new InventoryService(tiers);
    }

    private TicketTier tier(int quantity, int reserved, int sold) {
        TicketTier t = new TicketTier();
        t.setId(UUID.randomUUID());
        t.setEventId(UUID.randomUUID());
        t.setName("GA");
        t.setKind(TicketTierKind.STANDARD);
        t.setPriceMinor(1000);
        t.setQuantity(quantity);
        t.setReserved(reserved);
        t.setSold(sold);
        t.setEnabled(true);
        return t;
    }

    // ── reserve ────────────────────────────────────────────────────────────────

    @Test
    void reserve_happyPath_incrementsReserved() {
        TicketTier t = tier(100, 0, 0);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.reserve(t.getId(), 3);

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isEqualTo(3);
        assertThat(saved.getValue().getSold()).isZero();
    }

    @Test
    void reserve_addsToExistingReserved() {
        TicketTier t = tier(100, 10, 20);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.reserve(t.getId(), 5);

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isEqualTo(15);
    }

    @Test
    void reserve_atExactCapacity_succeeds() {
        // quantity=10, reserved=3, sold=4 → available=3 → reserving 3 ok
        TicketTier t = tier(10, 3, 4);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.reserve(t.getId(), 3);

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isEqualTo(6);
    }

    @Test
    void reserve_insufficient_throwsConflictWithFields() {
        // quantity=10, reserved=4, sold=4 → available=2; request 5 → conflict
        TicketTier t = tier(10, 4, 4);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> svc.reserve(t.getId(), 5))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.INVALID_STATE);
                    assertThat(apiEx.fields()).containsEntry("requested", "5");
                    assertThat(apiEx.fields()).containsEntry("available", "2");
                });

        verify(tiers, never()).save(any());
    }

    @Test
    void reserve_unknownTier_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(tiers.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.reserve(id, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── releaseReservation ─────────────────────────────────────────────────────

    @Test
    void release_decrementsReserved() {
        TicketTier t = tier(100, 7, 0);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.releaseReservation(t.getId(), 3);

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isEqualTo(4);
    }

    @Test
    void release_clampsAtZero_whenOverDecrement() {
        // reserved=2, release 5 → clamp to 0, not negative
        TicketTier t = tier(100, 2, 0);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.releaseReservation(t.getId(), 5);

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isZero();
    }

    @Test
    void release_zeroReserved_staysZero() {
        TicketTier t = tier(100, 0, 0);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.releaseReservation(t.getId(), 3);

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isZero();
    }

    // ── confirmSold ────────────────────────────────────────────────────────────

    @Test
    void confirmSold_decrementsReservedAndIncrementsSold() {
        TicketTier t = tier(100, 5, 10);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.confirmSold(t.getId(), 3);

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isEqualTo(2);
        assertThat(saved.getValue().getSold()).isEqualTo(13);
    }

    @Test
    void confirmSold_clampsReservedAtZero_butFullyIncrementsSold() {
        // reserved=1, confirm 3 → reserved clamps to 0 (logged warning), sold +3
        TicketTier t = tier(100, 1, 5);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.confirmSold(t.getId(), 3);

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isZero();
        assertThat(saved.getValue().getSold()).isEqualTo(8);
    }
}
