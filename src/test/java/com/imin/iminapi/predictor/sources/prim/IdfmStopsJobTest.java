package com.imin.iminapi.predictor.sources.prim;

import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.prim.IdfmStopsClient.Outcome;
import com.imin.iminapi.predictor.sources.prim.IdfmStopsClient.Stop;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.imin.iminapi.predictor.model.TransitSyncState;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdfmStopsJobTest {

    private static final Instant NOW = Instant.parse("2026-10-12T02:45:00Z");

    private final IdfmStopsClient client = mock(IdfmStopsClient.class);
    private final TransitStopStore store = mock(TransitStopStore.class);
    private final TransitSyncStateRepository states = mock(TransitSyncStateRepository.class);
    private final SourceGates gates = mock(SourceGates.class);
    private IdfmStopsJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(gates.isOn("prim")).thenReturn(true);
        when(states.findById("idfm-stops")).thenReturn(Optional.empty());
        ObjectProvider<IdfmStopsJob> self = mock(ObjectProvider.class);
        job = new IdfmStopsJob(client, store, states, gates, Clock.fixed(NOW, ZoneOffset.UTC), self, Runnable::run);
        when(self.getObject()).thenReturn(job);
    }

    @Test
    void okFetchReplacesThroughStore() {
        List<Stop> stops = List.of(new Stop("IDFM:22088", 48.8606, 2.3376));
        when(client.fetch()).thenReturn(new Outcome(Status.OK, stops));

        job.run();

        verify(store).replace(stops, NOW);
        verify(store, never()).recordAttempt(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"FAILED", "UNUSABLE"})
    void failedFetchKeepsStopsAndRecordsAttempt(Status status) {
        when(client.fetch()).thenReturn(new Outcome(status, List.of()));

        job.run();

        verify(store).recordAttempt(status, NOW);
        verify(store, never()).replace(any(), any());
    }

    @Test
    void replaceFailureRecordsFailedAttempt() {
        List<Stop> stops = List.of(new Stop("IDFM:22088", 48.8606, 2.3376));
        when(client.fetch()).thenReturn(new Outcome(Status.OK, stops));
        when(store.replace(stops, NOW)).thenThrow(new IllegalStateException("db down"));

        job.run();

        verify(store).recordAttempt(Status.FAILED, NOW);
    }

    @ParameterizedTest(name = "already synced = {0}")
    @ValueSource(booleans = {true, false})
    void startupSeedsOnlyWithoutASync(boolean synced) {
        TransitSyncState state = new TransitSyncState();
        state.setSyncedAt(NOW);
        when(states.findById("idfm-stops")).thenReturn(synced ? Optional.of(state) : Optional.empty());
        when(client.fetch()).thenReturn(new Outcome(Status.FAILED, List.of()));

        job.onStartup();

        verify(client, times(synced ? 0 : 1)).fetch();
    }

    @Test
    void gateOffMakesNoCall() {
        when(gates.isOn("prim")).thenReturn(false);

        job.run();
        job.onStartup();

        verifyNoInteractions(client, store);
    }
}
