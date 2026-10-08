package com.imin.iminapi.predictor.sources.prim;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.predictor.dto.PublicDataSourcesResponse.PublicDataSource;
import com.imin.iminapi.predictor.model.TransitSyncState;
import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Outcome;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Snapshot;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrimSyncJobTest {

    private static final Instant NOW = Instant.parse("2026-10-07T13:00:00Z");

    private final PrimDisruptionsClient client = mock(PrimDisruptionsClient.class);
    private final PrimWriter writer = mock(PrimWriter.class);
    private final TransitSyncStateRepository states = mock(TransitSyncStateRepository.class);
    private final SourceGates gates = mock(SourceGates.class);
    private final DataSourceCatalog catalog = mock(DataSourceCatalog.class);
    private PrimSyncJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(catalog.byId("idfm-prim")).thenReturn(Optional.of(new PublicDataSource("idfm-prim", "IDFM",
                List.of("transit_disruptions"), "Licence Mobilités", "https://l", "c", "https://u", "active", null)));
        when(catalog.syncSource("idfm-prim")).thenReturn(Optional.of("idfm-prim"));
        when(gates.isOn("prim")).thenReturn(true);
        ObjectProvider<PrimSyncJob> self = mock(ObjectProvider.class);
        job = new PrimSyncJob(client, writer, states, gates, new PrimProperties(), catalog,
                Clock.fixed(NOW, ZoneOffset.UTC), self, Runnable::run);
        when(self.getObject()).thenReturn(job);
    }

    @Test
    void okFetchReplacesThroughWriter() {
        Snapshot snapshot = new Snapshot(NOW, List.of(), 0);
        when(client.fetch()).thenReturn(new Outcome(Status.OK, snapshot));

        job.run();

        verify(writer).replace(snapshot, NOW);
        verify(writer, never()).recordAttempt(any(), any());
    }

    @Test
    void frozenFeedLogsErrorEvenWhenPollsSucceed() {
        Logger jobLog = (Logger) LoggerFactory.getLogger(PrimSyncJob.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        jobLog.addAppender(logs);
        try {
            when(client.fetch()).thenReturn(new Outcome(Status.OK, new Snapshot(NOW.minusSeconds(60), List.of(), 0)));
            job.run();
            assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.ERROR);

            int maxAge = new PrimProperties().getMaxAgeHours();
            when(client.fetch()).thenReturn(new Outcome(Status.OK,
                    new Snapshot(NOW.minusSeconds(maxAge * 3600L + 1), List.of(), 0)));
            job.run();
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                    .satisfies(e -> assertThat(e.getThrowableProxy()).isNotNull());
        } finally {
            jobLog.detachAppender(logs);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = "OK", mode = EnumSource.Mode.EXCLUDE)
    void failedFetchKeepsRowsAndRecordsAttempt(Status status) {
        when(client.fetch()).thenReturn(new Outcome(status, null));

        job.run();

        verify(writer).recordAttempt(status, NOW);
        verify(writer, never()).replace(any(), any());
    }

    @Test
    void storeFailureKeepsRowsRecordsTheAttemptAndDoesNotThrow() {
        Snapshot snapshot = new Snapshot(NOW, List.of(), 0);
        when(client.fetch()).thenReturn(new Outcome(Status.OK, snapshot));
        when(writer.replace(snapshot, NOW)).thenThrow(new IllegalStateException("db down"));

        job.run();

        // replace runs in one transaction, so its rollback leaves the previous rows; only the attempt is recorded
        verify(writer).recordAttempt(Status.FAILED, NOW);
    }

    @Test
    void gateOffMakesNoCall() {
        when(gates.isOn("prim")).thenReturn(false);

        job.run();
        job.onStartup();

        verifyNoInteractions(client, writer);
    }

    @Test
    void bootSeedsOnlyWhileNoPollSucceeded() {
        when(client.fetch()).thenReturn(new Outcome(Status.FAILED, null));
        TransitSyncState ok = new TransitSyncState();
        ok.setSyncedAt(NOW.minusSeconds(60));
        when(states.findById("idfm-prim")).thenReturn(Optional.of(ok));

        job.onStartup();
        verifyNoInteractions(client);

        when(states.findById("idfm-prim")).thenReturn(Optional.empty());
        job.onStartup();
        verify(client).fetch();
    }
}
