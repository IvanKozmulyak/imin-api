package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.engine.CalibrationSource.Counts;
import com.imin.iminapi.audienceplan.engine.CalibrationSource.Observations;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import com.imin.iminapi.audienceplan.repository.OutcomeStore;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.Calibration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalibrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");
    private static final UUID ORG_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID ORG_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID ORG_D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private final OutcomeStore store = mock(OutcomeStore.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final CalibrationService service = new CalibrationService(store, clock);

    // ── observations ─────────────────────────────────────────────────────────

    @Test
    void noRows_observesNothing_andVersionIsZero() {
        when(store.calibration()).thenReturn(List.of());

        assertThat(service.observations(ORG_A, "loyal", Fit.SAME)).isEqualTo(Observations.NONE);
        assertThat(service.version()).isZero();
    }

    @Test
    void own_isTheOrgsInvitationArms_summed_andIminExcludesOwn() {
        when(store.calibration()).thenReturn(List.of(
                org(ORG_A, "loyal", "same", "launch", 30, 6),
                org(ORG_A, "loyal", "same", "d3", 20, 2),
                org(ORG_B, "loyal", "same", "launch", 40, 4),
                org(ORG_C, "loyal", "same", "launch", 30, 3),
                org(ORG_D, "loyal", "same", "launch", 30, 3),
                imin("loyal", "same", "launch", 130, 16),
                imin("loyal", "same", "d3", 20, 2)));

        Observations o = service.observations(ORG_A, "loyal", Fit.SAME);

        assertThat(o.own()).isEqualTo(new Counts(50, 8));
        assertThat(o.imin()).isEqualTo(new Counts(100, 10));
    }

    @Test
    void holdoutRows_areNeverObservedAsInvitations() {
        when(store.calibration()).thenReturn(List.of(
                org(ORG_A, "loyal", "same", "holdout", 60, 3),
                imin("loyal", "same", "holdout", 60, 3)));

        assertThat(service.observations(ORG_A, "loyal", Fit.SAME)).isEqualTo(Observations.NONE);
    }

    @Test
    void anotherOrg_seesTheOtherOrgsArmsAsImin_only() {
        when(store.calibration()).thenReturn(List.of(
                org(ORG_A, "loyal", "same", "launch", 30, 6),
                org(ORG_C, "loyal", "same", "launch", 10, 1),
                org(ORG_D, "loyal", "same", "launch", 10, 1),
                imin("loyal", "same", "launch", 50, 8)));

        Observations o = service.observations(ORG_B, "loyal", Fit.SAME);

        assertThat(o.own()).isEqualTo(Counts.ZERO);
        assertThat(o.imin()).isEqualTo(new Counts(50, 8));
    }

    @Test
    void imin_isIgnored_whileFewerThanThreeOtherOrgsContributed() {
        when(store.calibration()).thenReturn(List.of(
                org(ORG_A, "loyal", "same", "launch", 30, 6),
                org(ORG_B, "loyal", "same", "launch", 20, 2),
                org(ORG_C, "loyal", "same", "launch", 20, 2),
                imin("loyal", "same", "launch", 70, 10)));

        // A sees two other orgs; D sees three.
        assertThat(service.observations(ORG_A, "loyal", Fit.SAME))
                .isEqualTo(new Observations(Counts.ZERO, new Counts(30, 6)));
        assertThat(service.observations(ORG_D, "loyal", Fit.SAME).imin()).isEqualTo(new Counts(70, 10));
        assertThat(CalibrationService.MIN_OTHER_ORGS).isEqualTo(3);
    }

    @Test
    void imin_contributors_countOnlyInvitationArmsWithPeopleInThatCell() {
        when(store.calibration()).thenReturn(List.of(
                org(ORG_B, "loyal", "same", "launch", 20, 2),
                org(ORG_C, "loyal", "same", "launch", 20, 2),
                org(ORG_D, "loyal", "same", "holdout", 60, 3),
                org(ORG_D, "loyal", "adjacent", "launch", 20, 2),
                imin("loyal", "same", "launch", 40, 4)));

        assertThat(service.observations(ORG_A, "loyal", Fit.SAME).imin()).isEqualTo(Counts.ZERO);
    }

    @Test
    void cells_areKeyedByClassAndFit() {
        when(store.calibration()).thenReturn(List.of(
                org(ORG_A, "loyal", "adjacent", "launch", 30, 6),
                imin("loyal", "adjacent", "launch", 30, 6)));

        assertThat(service.observations(ORG_A, "loyal", Fit.SAME)).isEqualTo(Observations.NONE);
        assertThat(service.observations(ORG_A, "repeat", Fit.ADJACENT)).isEqualTo(Observations.NONE);
        assertThat(service.observations(ORG_A, "loyal", Fit.ADJACENT).own()).isEqualTo(new Counts(30, 6));
    }

    @Test
    void iminBelowOwn_isClampedToZero_notAnError() {
        when(store.calibration()).thenReturn(List.of(
                org(ORG_A, "loyal", "same", "launch", 30, 6),
                org(ORG_B, "loyal", "same", "launch", 1, 0),
                org(ORG_C, "loyal", "same", "launch", 1, 0),
                org(ORG_D, "loyal", "same", "launch", 1, 0),
                imin("loyal", "same", "launch", 10, 1)));

        assertThat(service.observations(ORG_A, "loyal", Fit.SAME).imin()).isEqualTo(Counts.ZERO);
    }

    // ── version and snapshot ─────────────────────────────────────────────────

    @Test
    void version_isNonZeroWithRows_andChangesWithTheCounts() {
        when(store.calibration()).thenReturn(List.of(org(ORG_A, "loyal", "same", "launch", 30, 6)));
        int first = service.version();

        service.invalidate();
        when(store.calibration()).thenReturn(List.of(org(ORG_A, "loyal", "same", "launch", 30, 7)));
        int second = service.version();

        assertThat(first).isNotZero();
        assertThat(second).isNotZero().isNotEqualTo(first);
    }

    @Test
    void snapshot_isReused_untilItIsTenMinutesOld() {
        when(store.calibration()).thenReturn(List.of());
        service.version();
        now.set(NOW.plus(Duration.ofMinutes(10)).minusSeconds(1));
        service.version();
        verify(store, times(1)).calibration();

        now.set(NOW.plus(Duration.ofMinutes(10)));
        service.version();
        verify(store, times(2)).calibration();
    }

    @Test
    void invalidate_forcesTheNextReadToLoad() {
        when(store.calibration()).thenReturn(List.of());
        service.version();
        service.invalidate();
        service.version();
        verify(store, times(2)).calibration();
    }

    @Test
    void aFailedReload_keepsThePreviousSnapshot() {
        when(store.calibration()).thenReturn(List.of(org(ORG_A, "loyal", "same", "launch", 30, 6)));
        int version = service.version();
        now.set(NOW.plus(Duration.ofMinutes(10)));
        when(store.calibration()).thenThrow(new IllegalStateException("db down"));

        assertThat(service.version()).isEqualTo(version).isNotZero();
        assertThat(service.observations(ORG_A, "loyal", Fit.SAME).own()).isEqualTo(new Counts(30, 6));
    }

    @Test
    void aFailedFirstLoad_isEmpty() {
        when(store.calibration()).thenThrow(new IllegalStateException("db down"));

        assertThat(service.observations(ORG_A, "loyal", Fit.SAME)).isEqualTo(Observations.NONE);
        assertThat(service.version()).isZero();
    }

    // ── rebuild ──────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void rebuild_keepsOrgRowsWithPeople_andAddsOneIminRowPerCellAndArm() {
        when(store.orgCalibrationFromOutcomes()).thenReturn(List.of(
                org(ORG_A, "loyal", "same", "launch", 30, 6, 1),
                org(ORG_B, "loyal", "same", "launch", 20, 2, 2),
                org(ORG_A, "loyal", "same", "holdout", 60, 3, 1),
                org(ORG_B, "repeat", "same", "d3", 0, 0, 1)));

        List<Calibration> rows = service.rebuild();

        ArgumentCaptor<List<Calibration>> written = ArgumentCaptor.forClass(List.class);
        verify(store).replaceCalibration(written.capture(), eq(NOW));
        assertThat(written.getValue()).isEqualTo(rows).containsExactlyInAnyOrder(
                org(ORG_A, "loyal", "same", "launch", 30, 6, 1),
                org(ORG_B, "loyal", "same", "launch", 20, 2, 2),
                org(ORG_A, "loyal", "same", "holdout", 60, 3, 1),
                new Calibration("imin", OutcomeStore.IMIN_ORG, "loyal", "same", "launch", 50, 8, 3),
                new Calibration("imin", OutcomeStore.IMIN_ORG, "loyal", "same", "holdout", 60, 3, 1));
    }

    @Test
    void rebuild_withNoOutcomes_clearsTheTable() {
        when(store.orgCalibrationFromOutcomes()).thenReturn(List.of());

        assertThat(service.rebuild()).isEmpty();
        verify(store).replaceCalibration(eq(List.of()), any());
    }

    private static Calibration org(UUID org, String cls, String fit, String arm, int n, int bought) {
        return org(org, cls, fit, arm, n, bought, 1);
    }

    private static Calibration org(UUID org, String cls, String fit, String arm, int n, int bought, int events) {
        return new Calibration("org", org, cls, fit, arm, n, bought, events);
    }

    private static Calibration imin(String cls, String fit, String arm, int n, int bought) {
        return new Calibration("imin", OutcomeStore.IMIN_ORG, cls, fit, arm, n, bought, 1);
    }
}
