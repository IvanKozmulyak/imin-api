package com.imin.iminapi.predictor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.service.DateCheckService.RadarOutcome;
import com.imin.iminapi.predictor.service.DateCheckService.RadarRun;
import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RadarJobTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private final EventRepository events = mock(EventRepository.class);
    private final DateCheckService service = mock(DateCheckService.class);
    private final ReforecastAlertNotifier notifier = mock(ReforecastAlertNotifier.class);

    private RadarJob job(boolean enabled, boolean radar) {
        DateCheckProperties props = new DateCheckProperties();
        props.setEnabled(enabled);
        props.setRadarEnabled(radar);
        return new RadarJob(props, events, service, notifier, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void offFlagNeverQueriesEvents() {
        assertThat(job(true, false).pass()).isEqualTo(new RadarJob.Result(0, 0, 0, 0));
        verify(events, never()).findRadarCandidateIds(any(), any());
        verifyNoInteractions(service);
    }

    @Test
    void dateCheckOffNeverQueriesEvents() {
        assertThat(job(false, true).pass()).isEqualTo(new RadarJob.Result(0, 0, 0, 0));
        verify(events, never()).findRadarCandidateIds(any(), any());
        verifyNoInteractions(service);
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        UUID broken = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        when(events.findRadarCandidateIds(NOW, NOW.plus(Duration.ofDays(32)))).thenReturn(List.of(broken, fine));
        when(service.radarRerun(broken)).thenThrow(new IllegalStateException("boom"));
        when(service.radarRerun(fine)).thenReturn(RadarRun.of(RadarOutcome.RAN));

        assertThat(job(true, true).pass()).isEqualTo(new RadarJob.Result(2, 1, 0, 1));
        verify(service).radarRerun(fine);
        verifyNoInteractions(notifier);
    }

    // --- alerts ---

    private static RadarAlertRule.Alert alertFor(UUID eventId) {
        return new RadarAlertRule.Alert(eventId, UUID.randomUUID(), LocalDate.of(2026, 10, 15), "good", "move", 0, 8);
    }

    @Test
    void ranWithAlertNotifies() {
        UUID id = UUID.randomUUID();
        RadarAlertRule.Alert alert = alertFor(id);
        when(events.findRadarCandidateIds(NOW, NOW.plus(Duration.ofDays(32)))).thenReturn(List.of(id));
        when(service.radarRerun(id)).thenReturn(new RadarRun(RadarOutcome.RAN, alert));

        assertThat(job(true, true).pass()).isEqualTo(new RadarJob.Result(1, 1, 0, 0));
        verify(notifier).notifyRadarWorsened(alert);
    }

    @Test
    void notifierFailureDoesNotStopThePass() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RadarAlertRule.Alert first = alertFor(a);
        RadarAlertRule.Alert second = alertFor(b);
        when(events.findRadarCandidateIds(NOW, NOW.plus(Duration.ofDays(32)))).thenReturn(List.of(a, b));
        when(service.radarRerun(a)).thenReturn(new RadarRun(RadarOutcome.RAN, first));
        when(service.radarRerun(b)).thenReturn(new RadarRun(RadarOutcome.RAN, second));
        doThrow(new IllegalStateException("mail down")).when(notifier).notifyRadarWorsened(first);

        RadarJob job = job(true, true);
        RadarJob.Result[] result = new RadarJob.Result[1];
        List<ILoggingEvent> logged = capture(() -> result[0] = job.pass());

        assertThat(result[0]).isEqualTo(new RadarJob.Result(2, 2, 0, 0));
        verify(notifier).notifyRadarWorsened(second);
        assertThat(logged).filteredOn(e -> e.getLevel() == Level.WARN).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("alert failed for event " + a);
            assertThat(e.getThrowableProxy().getMessage()).isEqualTo("mail down");
        });
    }

    @Test
    void duplicateRunCountsAsSkipped() {
        UUID dup = UUID.randomUUID();
        UUID notDue = UUID.randomUUID();
        when(events.findRadarCandidateIds(NOW, NOW.plus(Duration.ofDays(32)))).thenReturn(List.of(dup, notDue));
        when(service.radarRerun(dup)).thenThrow(new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("Unique index or primary key violation: \"PUBLIC.UQ_DATE_CHECK_RADAR_RUN_INDEX\"")));
        when(service.radarRerun(notDue)).thenReturn(RadarRun.of(RadarOutcome.NOT_DUE));

        assertThat(job(true, true).pass()).isEqualTo(new RadarJob.Result(2, 0, 2, 0));
        verifyNoInteractions(notifier);
    }

    @Test
    void otherConstraintCountsAsFailed() {
        UUID broken = UUID.randomUUID();
        when(events.findRadarCandidateIds(NOW, NOW.plus(Duration.ofDays(32)))).thenReturn(List.of(broken));
        when(service.radarRerun(broken)).thenThrow(new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("violates check constraint \"ck_date_check_radar_shape\"")));

        assertThat(job(true, true).pass()).isEqualTo(new RadarJob.Result(1, 0, 0, 1));
    }

    // --- pass outcome ---

    @Test
    void allFailedCountsEveryRunAsFailed() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(events.findRadarCandidateIds(NOW, NOW.plus(Duration.ofDays(32)))).thenReturn(List.of(a, b));
        when(service.radarRerun(a)).thenThrow(new IllegalStateException("first"));
        when(service.radarRerun(b)).thenThrow(new IllegalStateException("second"));

        assertThat(job(true, true).pass()).isEqualTo(new RadarJob.Result(2, 0, 0, 2));
    }

    @Test
    void cleanPassCountsOneRun() {
        UUID fine = UUID.randomUUID();
        when(events.findRadarCandidateIds(NOW, NOW.plus(Duration.ofDays(32)))).thenReturn(List.of(fine));
        when(service.radarRerun(fine)).thenReturn(RadarRun.of(RadarOutcome.RAN));

        assertThat(job(true, true).pass()).isEqualTo(new RadarJob.Result(1, 1, 0, 0));
        verifyNoInteractions(notifier);
    }

    private static List<ILoggingEvent> capture(Runnable r) {
        Logger logger = (Logger) LoggerFactory.getLogger(RadarJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            r.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
