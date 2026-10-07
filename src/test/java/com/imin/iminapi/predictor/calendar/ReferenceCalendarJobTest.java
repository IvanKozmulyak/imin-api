package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceCalendarJobTest {

    // 23:30Z on Saturday is already Sunday in Paris
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T23:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate PARIS_TODAY = LocalDate.of(2026, 10, 4);

    private final CalendarSource a = mock(CalendarSource.class);
    private final CalendarSource b = mock(CalendarSource.class);
    private final ReferenceCalendarWriter writer = mock(ReferenceCalendarWriter.class);
    private final ReferenceCalendarEntryRepository repository = mock(ReferenceCalendarEntryRepository.class);
    private final CalendarSyncProperties props = new CalendarSyncProperties();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ReferenceCalendarJob> self = mock(ObjectProvider.class);

    private ReferenceCalendarJob job() {
        return job(Runnable::run);
    }

    private ReferenceCalendarJob job(Executor executor) {
        ReferenceCalendarJob job = new ReferenceCalendarJob(List.of(a, b), writer, repository, props, CLOCK, self, executor);
        when(self.getObject()).thenReturn(job);
        return job;
    }

    private static CalendarSource.Batch batch() {
        LocalDate d = LocalDate.of(2026, 11, 1);
        return new CalendarSource.Batch("u", Set.of("holiday"), d, d,
                List.of(new CalendarRow("FR", "", d, null, "holiday", "Toussaint", "u")));
    }

    @Test
    void disabledMakesNoCall() {
        props.setSyncEnabled(false);

        job().run();
        job().onStartup();

        verify(a, never()).fetch(any());
        verify(b, never()).fetch(any());
        verify(repository, never()).count();
    }

    @Test
    void runFetchesEachSourceWithTheParisDateAndWritesItsBatches() {
        CalendarSource.Batch batch = batch();
        when(a.fetch(PARIS_TODAY)).thenReturn(List.of(batch));
        when(b.fetch(PARIS_TODAY)).thenReturn(List.of());

        job().run();

        verify(writer).replace(batch);
        verify(b).fetch(PARIS_TODAY);
    }

    @Test
    void startupSyncsOnlyWhenEmpty() {
        when(a.fetch(any())).thenReturn(List.of());
        when(b.fetch(any())).thenReturn(List.of());
        when(repository.count()).thenReturn(5L);
        job().onStartup();
        verify(a, never()).fetch(any());
        verify(self, never()).getObject();

        when(repository.count()).thenReturn(0L);
        job().onStartup();
        verify(self).getObject();
        verify(a).fetch(PARIS_TODAY);
    }

    @Test
    void startupSyncsWhenASourcePrefixHasNoRows() {
        when(a.fetch(any())).thenReturn(List.of());
        when(b.fetch(any())).thenReturn(List.of());
        when(repository.count()).thenReturn(5L);
        when(b.scopePrefix()).thenReturn("https://new.example/");
        when(repository.existsBySourceUrlStartingWith("https://new.example/")).thenReturn(false);

        job().onStartup();

        verify(self).getObject();
        verify(a).fetch(PARIS_TODAY);
        verify(b).fetch(PARIS_TODAY);
    }

    @Test
    void startupSkipsWhenEveryPrefixHasRows() {
        when(repository.count()).thenReturn(5L);
        when(a.scopePrefix()).thenReturn("https://a.example/");
        when(b.scopePrefix()).thenReturn("https://b.example/");
        when(repository.existsBySourceUrlStartingWith(any())).thenReturn(true);

        job().onStartup();

        verify(repository).existsBySourceUrlStartingWith("https://a.example/");
        verify(repository).existsBySourceUrlStartingWith("https://b.example/");
        verify(self, never()).getObject();
        verify(a, never()).fetch(any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"executor rejects", "run fails", "count fails"})
    void startupFailureIsSwallowed(String failure) {
        ReferenceCalendarJob job;
        switch (failure) {
            case "executor rejects" -> {
                when(repository.count()).thenReturn(0L);
                job = job(r -> { throw new RejectedExecutionException("full"); });
            }
            case "run fails" -> {
                when(repository.count()).thenReturn(0L);
                job = job();
                when(self.getObject()).thenThrow(new IllegalStateException("no proxy"));
            }
            default -> {
                when(repository.count()).thenThrow(new IllegalStateException("db down"));
                job = job();
            }
        }

        job.onStartup();

        if (failure.equals("executor rejects")) verify(self, never()).getObject();
        if (failure.equals("run fails")) verify(self).getObject();
        verify(a, never()).fetch(any());
    }

    @Test
    void oneSourceFailingDoesNotStopOthers() {
        CalendarSource.Batch batch = batch();
        when(a.key()).thenReturn("a");
        when(a.fetch(any())).thenThrow(new IllegalStateException("boom"));
        when(b.fetch(any())).thenReturn(List.of(batch));

        job().run();

        verify(writer).replace(batch);
    }
}
