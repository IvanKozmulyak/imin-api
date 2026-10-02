package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.stripe.StripeProductService.SyncOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TierStripeSyncQueueTest {

    /** Stores tasks instead of running them, so each test decides when a run happens. */
    static final class CapturingExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        int rejectNext;

        @Override
        public void execute(Runnable command) {
            if (rejectNext > 0) {
                rejectNext--;
                throw new RejectedExecutionException("full");
            }
            tasks.add(command);
        }

        void runNext() {
            tasks.remove(0).run();
        }
    }

    TicketTierRepository tiers = mock(TicketTierRepository.class);
    EventRepository events = mock(EventRepository.class);
    StripeProductService products = mock(StripeProductService.class);
    CapturingExecutor executor = new CapturingExecutor();
    TierStripeSyncQueue queue = new TierStripeSyncQueue(tiers, events, products, executor);

    UUID tierId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    TicketTier tier;
    Event event;

    @BeforeEach
    void setUp() {
        tier = new TicketTier();
        tier.setId(tierId);
        tier.setEventId(eventId);
        event = new Event();
        event.setId(eventId);
        when(tiers.findById(tierId)).thenReturn(Optional.of(tier));
        when(events.findActive(eventId)).thenReturn(Optional.of(event));
    }

    @Test
    void request_runsSyncOnFreshlyLoadedTierAndEvent() {
        when(products.syncTier(any(), any())).thenReturn(SyncOutcome.SYNCED);

        queue.onCommitted(new TierStripeSyncRequested(tierId));
        assertThat(executor.tasks).hasSize(1);
        verify(products, never()).syncTier(any(), any());

        executor.runNext();

        verify(tiers).findById(tierId);
        verify(events).findActive(eventId);
        verify(products).syncTier(same(tier), same(event));
        assertThat(executor.tasks).isEmpty();
    }

    @Test
    void tierGone_noStripeCall() {
        when(tiers.findById(tierId)).thenReturn(Optional.empty());

        queue.request(tierId, 0);
        executor.runNext();

        verify(products, never()).syncTier(any(), any());
    }

    @Test
    void eventGoneOrSoftDeleted_noStripeCall() {
        when(events.findActive(eventId)).thenReturn(Optional.empty());

        queue.request(tierId, 0);
        executor.runNext();

        verify(products, never()).syncTier(any(), any());
    }

    @Test
    void repeatWhileQueued_isMergedIntoOneRun() {
        queue.request(tierId, 0);
        queue.request(tierId, 0);

        assertThat(executor.tasks).hasSize(1);
        executor.runNext();
        verify(products, times(1)).syncTier(any(), any());
    }

    @Test
    void repeatDuringRun_queuesAnotherRun() {
        when(products.syncTier(any(), any())).thenAnswer(inv -> {
            queue.request(tierId, 0);
            return SyncOutcome.SYNCED;
        });

        queue.request(tierId, 0);
        executor.runNext();

        assertThat(executor.tasks).hasSize(1);
    }

    @Test
    void rejectedByExecutor_clearsPending_nextRequestQueues() {
        executor.rejectNext = 1;

        assertThatCode(() -> queue.request(tierId, 0)).doesNotThrowAnyException();
        assertThat(executor.tasks).isEmpty();

        queue.request(tierId, 0);
        assertThat(executor.tasks).hasSize(1);
    }

    @Test
    void executorThrowsOtherRuntimeException_nothingThrownToCaller_nextRequestQueues() {
        java.util.List<Runnable> accepted = new java.util.ArrayList<>();
        boolean[] broken = {true};
        Executor flaky = command -> {
            if (broken[0]) throw new IllegalStateException("broken executor");
            accepted.add(command);
        };
        TierStripeSyncQueue withFlaky = new TierStripeSyncQueue(tiers, events, products, flaky);

        assertThatCode(() -> withFlaky.onCommitted(new TierStripeSyncRequested(tierId)))
                .doesNotThrowAnyException();
        verify(products, never()).syncTier(any(), any());

        broken[0] = false;
        withFlaky.request(tierId, 0);
        assertThat(accepted).as("the failed request did not leave the tier pending").hasSize(1);
    }

    @Test
    void staleOutcome_retriesOnce_thenStops() {
        when(products.syncTier(any(), any())).thenReturn(SyncOutcome.STALE);

        queue.request(tierId, 0);
        executor.runNext();
        assertThat(executor.tasks).as("one retry after a stale write").hasSize(1);

        executor.runNext();
        assertThat(executor.tasks).as("no second retry").isEmpty();
        verify(products, times(2)).syncTier(any(), any());
    }

    @Test
    void failedOutcome_noRetry() {
        when(products.syncTier(any(), any())).thenReturn(SyncOutcome.FAILED);

        queue.request(tierId, 0);
        executor.runNext();

        assertThat(executor.tasks).isEmpty();
        verify(products, times(1)).syncTier(any(), any());
    }

    @Test
    void syncThrows_pendingCleared_noThrowOutOfTask() {
        when(products.syncTier(any(), any())).thenThrow(new IllegalStateException("boom"));

        queue.request(tierId, 0);
        assertThatCode(executor::runNext).doesNotThrowAnyException();

        queue.request(tierId, 0);
        assertThat(executor.tasks).hasSize(1);
    }
}
