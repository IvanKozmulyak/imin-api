package com.imin.iminapi.service.event;

import com.imin.iminapi.model.ReservationStatus;
import com.imin.iminapi.model.TicketReservation;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.TicketReservationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-21T12:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(1800);

    private TicketTierRepository tiers;
    private TicketReservationRepository reservations;
    private InventoryService svc;

    @BeforeEach
    void setUp() {
        tiers = mock(TicketTierRepository.class);
        reservations = mock(TicketReservationRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        svc = new InventoryService(tiers, reservations, clock);

        // Default: persisting a reservation echoes it back with a generated id so
        // tests don't have to wire that up per test.
        when(reservations.save(any(TicketReservation.class))).thenAnswer(inv -> {
            TicketReservation r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
    }

    private TicketTier tier(int quantity, int reserved, int sold) {
        TicketTier t = new TicketTier();
        t.setId(UUID.randomUUID());
        t.setEventId(UUID.randomUUID());
        t.setName("GA");
        t.setPriceMinor(1000);
        t.setQuantity(quantity);
        t.setReserved(reserved);
        t.setSold(sold);
        t.setEnabled(true);
        return t;
    }

    private TicketReservation held(UUID tierId, int qty) {
        TicketReservation r = new TicketReservation();
        r.setId(UUID.randomUUID());
        r.setTierId(tierId);
        r.setQty(qty);
        r.setStatus(ReservationStatus.HELD);
        r.setExpiresAt(EXPIRES);
        return r;
    }

    // ── reserve ────────────────────────────────────────────────────────────────

    @Test
    void reserve_happyPath_incrementsReservedAndWritesHeldRow() {
        TicketTier t = tier(100, 0, 0);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        UUID id = svc.reserve(t.getId(), 3, EXPIRES, "cs_test_abc");

        assertThat(id).isNotNull();
        ArgumentCaptor<TicketTier> savedTier = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(savedTier.capture());
        assertThat(savedTier.getValue().getReserved()).isEqualTo(3);

        ArgumentCaptor<TicketReservation> savedRes = ArgumentCaptor.forClass(TicketReservation.class);
        verify(reservations).save(savedRes.capture());
        TicketReservation r = savedRes.getValue();
        assertThat(r.getTierId()).isEqualTo(t.getId());
        assertThat(r.getQty()).isEqualTo(3);
        assertThat(r.getStripeSessionId()).isEqualTo("cs_test_abc");
        assertThat(r.getExpiresAt()).isEqualTo(EXPIRES);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(r.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void reserve_atExactCapacity_succeeds() {
        // quantity=10, reserved=3, sold=4 → available=3 → reserving 3 ok
        TicketTier t = tier(10, 3, 4);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.reserve(t.getId(), 3, EXPIRES, null);

        ArgumentCaptor<TicketTier> savedTier = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(savedTier.capture());
        assertThat(savedTier.getValue().getReserved()).isEqualTo(6);
    }

    @Test
    void reserve_insufficient_throwsConflictWithFields() {
        // quantity=10, reserved=4, sold=4 → available=2; request 5 → conflict
        TicketTier t = tier(10, 4, 4);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> svc.reserve(t.getId(), 5, EXPIRES, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.INVALID_STATE);
                    assertThat(apiEx.fields()).containsEntry("requested", "5");
                    assertThat(apiEx.fields()).containsEntry("available", "2");
                });

        verify(tiers, never()).save(any());
        verify(reservations, never()).save(any());
    }

    @Test
    void reserve_unknownTier_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(tiers.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.reserve(id, 1, EXPIRES, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── releaseReservation ─────────────────────────────────────────────────────

    @Test
    void release_decrementsReservedAndMarksRowReleased() {
        TicketTier t = tier(100, 7, 0);
        TicketReservation r = held(t.getId(), 3);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));
        when(reservations.markReleased(eq(r.getId()), eq(NOW), eq("WEBHOOK_EXPIRED"))).thenReturn(1);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.releaseReservation(r.getId(), "WEBHOOK_EXPIRED");

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isEqualTo(4);
    }

    @Test
    void release_unknownReservation_isNoOp() {
        UUID unknown = UUID.randomUUID();
        when(reservations.findById(unknown)).thenReturn(Optional.empty());

        svc.releaseReservation(unknown, "SWEEPER");

        verify(tiers, never()).save(any());
        verify(reservations, never()).markReleased(any(), any(), anyString());
    }

    @Test
    void release_alreadyReleased_isNoOp() {
        TicketReservation r = held(UUID.randomUUID(), 3);
        r.setStatus(ReservationStatus.RELEASED);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));

        svc.releaseReservation(r.getId(), "WEBHOOK_EXPIRED");

        verify(reservations, never()).markReleased(any(), any(), anyString());
        verify(tiers, never()).save(any());
    }

    @Test
    void release_lostRaceOnConditionalUpdate_isNoOp() {
        // findById sees HELD, but the conditional UPDATE returns 0 rows because
        // a concurrent caller transitioned it first. We must not decrement again.
        TicketTier t = tier(100, 7, 0);
        TicketReservation r = held(t.getId(), 3);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));
        when(reservations.markReleased(eq(r.getId()), eq(NOW), anyString())).thenReturn(0);

        svc.releaseReservation(r.getId(), "WEBHOOK_EXPIRED");

        verify(tiers, never()).save(any());
    }

    @Test
    void release_clampsAtZero_whenTierReservedHasDrifted() {
        // tier.reserved=2, reservation.qty=5 → counter is somehow behind. Clamp,
        // don't go negative.
        TicketTier t = tier(100, 2, 0);
        TicketReservation r = held(t.getId(), 5);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));
        when(reservations.markReleased(eq(r.getId()), eq(NOW), anyString())).thenReturn(1);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.releaseReservation(r.getId(), "SWEEPER");

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isZero();
    }

    // ── confirmSold ────────────────────────────────────────────────────────────

    @Test
    void confirmSold_heldRow_decrementsReservedAndIncrementsSold() {
        TicketTier t = tier(100, 5, 10);
        TicketReservation r = held(t.getId(), 3);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));
        when(reservations.markConfirmed(eq(r.getId()), eq(NOW))).thenReturn(1);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.confirmSold(r.getId());

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isEqualTo(2);
        assertThat(saved.getValue().getSold()).isEqualTo(13);
    }

    @Test
    void confirmSold_alreadyConfirmed_isNoOp() {
        TicketReservation r = held(UUID.randomUUID(), 3);
        r.setStatus(ReservationStatus.CONFIRMED);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));

        svc.confirmSold(r.getId());

        verify(reservations, never()).markConfirmed(any(), any());
        verify(tiers, never()).save(any());
    }

    @Test
    void confirmSold_alreadyReleased_creditsSoldWithoutReservedDecrement() {
        // Sweeper raced ahead of a delayed payment_intent.succeeded. Stripe says
        // the money moved → we still owe the buyer their tickets, but reserved
        // is already 0 from the prior release. Sold goes up; reserved untouched.
        TicketTier t = tier(100, 0, 10);
        TicketReservation r = held(t.getId(), 3);
        r.setStatus(ReservationStatus.RELEASED);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.confirmSold(r.getId());

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isZero();
        assertThat(saved.getValue().getSold()).isEqualTo(13);
    }

    @Test
    void confirmSold_clampsReservedAtZero_whenTierHasDrifted() {
        TicketTier t = tier(100, 1, 5);
        TicketReservation r = held(t.getId(), 3);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));
        when(reservations.markConfirmed(eq(r.getId()), eq(NOW))).thenReturn(1);
        when(tiers.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

        svc.confirmSold(r.getId());

        ArgumentCaptor<TicketTier> saved = ArgumentCaptor.forClass(TicketTier.class);
        verify(tiers).save(saved.capture());
        assertThat(saved.getValue().getReserved()).isZero();
        assertThat(saved.getValue().getSold()).isEqualTo(8);
    }

    // ── attachSessionId ────────────────────────────────────────────────────────

    @Test
    void attachSessionId_setsField_whenStillHeld() {
        TicketReservation r = held(UUID.randomUUID(), 1);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));

        svc.attachSessionId(r.getId(), "cs_test_attach");

        ArgumentCaptor<TicketReservation> saved = ArgumentCaptor.forClass(TicketReservation.class);
        verify(reservations).save(saved.capture());
        assertThat(saved.getValue().getStripeSessionId()).isEqualTo("cs_test_attach");
    }

    @Test
    void attachSessionId_skipsWhenAlreadyTerminal() {
        TicketReservation r = held(UUID.randomUUID(), 1);
        r.setStatus(ReservationStatus.CONFIRMED);
        when(reservations.findById(r.getId())).thenReturn(Optional.of(r));

        svc.attachSessionId(r.getId(), "cs_test_attach");

        verify(reservations, never()).save(any());
    }
}
