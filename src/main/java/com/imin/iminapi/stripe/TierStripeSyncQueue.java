package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.stripe.StripeProductService.SyncOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Syncs tiers to Stripe off the request thread; organizer writes and the sweeper both feed it. */
@Component
public class TierStripeSyncQueue {

    private static final Logger log = LoggerFactory.getLogger(TierStripeSyncQueue.class);

    private final TicketTierRepository tiers;
    private final EventRepository events;
    private final StripeProductService productService;
    private final Executor executor;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public TierStripeSyncQueue(TicketTierRepository tiers,
                               EventRepository events,
                               StripeProductService productService,
                               @Qualifier("tierStripeSyncExecutor") Executor executor) {
        this.tiers = tiers;
        this.events = events;
        this.productService = productService;
        this.executor = executor;
    }

    /** Runs after commit on one thread, so no lock is held during the Stripe call. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(TierStripeSyncRequested e) {
        request(e.tierId(), 0);
    }

    /** Queues one sync of the tier unless one is already queued; never throws into the caller. */
    public void request(UUID tierId, int attempt) {
        try {
            if (pending.add(tierId)) {
                try {
                    executor.execute(() -> run(tierId, attempt));
                } catch (RejectedExecutionException ex) {
                    pending.remove(tierId);
                    log.warn("Tier Stripe sync rejected (queue full or shutting down), tier {} left to the sweeper", tierId);
                } catch (RuntimeException ex) {
                    pending.remove(tierId);
                    throw ex;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Could not queue Stripe sync for tier {}", tierId, ex);
        }
    }

    private void run(UUID tierId, int attempt) {
        // The task drops its id first, so a commit during a run queues another.
        pending.remove(tierId);
        try {
            Optional<TicketTier> tier = tiers.findById(tierId);
            if (tier.isEmpty()) return;
            Optional<Event> event = events.findActive(tier.get().getEventId());
            if (event.isEmpty()) return;
            SyncOutcome outcome = productService.syncTier(tier.get(), event.get());
            if (outcome == SyncOutcome.STALE && attempt == 0) request(tierId, 1);
        } catch (RuntimeException ex) {
            log.warn("Stripe sync of tier {} failed", tierId, ex);
        }
    }
}
