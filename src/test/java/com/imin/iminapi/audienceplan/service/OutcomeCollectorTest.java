package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.repository.OutcomeStore;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.EventRef;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutcomeCollectorTest {

    private static final Instant NOW = Instant.parse("2026-11-01T07:00:00Z");
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final OutcomeStore store = mock(OutcomeStore.class);
    private final CalibrationService calibration = mock(CalibrationService.class);
    private final AudiencePlanAccess access = mock(AudiencePlanAccess.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutcomeCollector> self = mock(ObjectProvider.class);
    private final OutcomeCollector collector =
            new OutcomeCollector(store, calibration, access, txManager, Clock.fixed(NOW, ZoneOffset.UTC), self);

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(access.isEnabled(ORG)).thenReturn(true);
        when(store.experiments(any(), any())).thenReturn(List.of());
        when(store.tallies(any(), any(), any(), any())).thenReturn(Map.of());
        when(store.firstAssignedAt(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void oneEventFailing_doesNotStopTheNextOneBeingWritten() {
        EventRef broken = past(UUID.randomUUID());
        EventRef fine = past(UUID.randomUUID());
        when(store.eventsWithExperiments(any(), any())).thenReturn(List.of(broken, fine));
        when(store.storedEvent(ORG, broken.eventId())).thenThrow(new IllegalStateException("boom"));
        when(store.storedEvent(ORG, fine.eventId())).thenReturn(Optional.empty());

        OutcomeCollector.Result r = collector.run();

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.written()).isEqualTo(1);
        assertThat(r.calibrated()).isTrue();
        verify(store, never()).replaceOutcome(eq(ORG), eq(broken.eventId()), anyString(), anyList(), anyMap(), any(),
                any(), any());
        verify(store).replaceOutcome(eq(ORG), eq(fine.eventId()), eq("d1"), anyList(), anyMap(), any(), any(),
                eq(NOW));
        verify(calibration).rebuild();
    }

    @Test
    void aFailedCalibrationRebuild_reportsNotCalibrated_andStillInvalidatesTheSnapshot() {
        EventRef fine = past(UUID.randomUUID());
        when(store.eventsWithExperiments(any(), any())).thenReturn(List.of(fine));
        when(store.storedEvent(ORG, fine.eventId())).thenReturn(Optional.empty());
        when(calibration.rebuild()).thenThrow(new IllegalStateException("db down"));

        OutcomeCollector.Result r = collector.run();

        assertThat(r.written()).isEqualTo(1);
        assertThat(r.calibrated()).isFalse();
        verify(calibration).invalidate();
    }

    @Test
    void wiring_dailyAt8Paris_beforeThePlanRefresh_underAShedLock() throws Exception {
        Scheduled scheduled = OutcomeCollector.class.getMethod("scheduled").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 0 8 * * *");
        assertThat(scheduled.zone()).isEqualTo("Europe/Paris");
        Scheduled refresh = PlanRefreshJob.class.getMethod("scheduled").getAnnotation(Scheduled.class);
        assertThat(refresh.cron()).isEqualTo("0 0 9 * * *");
        assertThat(refresh.zone()).isEqualTo(scheduled.zone());
        assertThat(OutcomeCollector.class.getMethod("run").getAnnotation(SchedulerLock.class).name())
                .isEqualTo("audience_outcomes");
    }

    /** Started 3 days ago with no end, so the door closed 2.5 days ago and D+1 is due. */
    private static EventRef past(UUID eventId) {
        return new EventRef(eventId, ORG, NOW.minus(Duration.ofDays(3)), null);
    }
}
