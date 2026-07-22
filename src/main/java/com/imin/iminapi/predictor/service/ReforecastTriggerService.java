package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.ReforecastTrigger;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.event.SalesMilestoneReachedEvent;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires re-forecast recomputes off event/milestone triggers (spec §4.2, task 86cav479j).
 *
 * <p><b>Milestone hook.</b> Rides the fulfilment path via {@link TicketsIssuedEvent} (published
 * AFTER_COMMIT when a paid order is issued — where sold counts change). On each, it computes the
 * event-level 25/50/75% sold crossing relative to the last re-forecast's sold count
 * ({@link ReforecastMilestones}); a newly-crossed threshold requests a recompute.
 *
 * <p><b>Burst debounce (leading-edge).</b> {@link #requestRecompute} coalesces triggers for the
 * same event inside {@code imin.predictor.reforecast-debounce-seconds} (default 60): the first
 * fires immediately, the rest are dropped so one sales/edit burst does not recompute N times. The
 * daily cron and manual refresh bypass the debounce (they always run).
 *
 * <p>In-memory dedup by design (single-instance Railway deploy): a restart just re-arms the
 * window; the ledger, not this map, is the source of truth.
 */
@Service
public class ReforecastTriggerService {

    private static final Logger log = LoggerFactory.getLogger(ReforecastTriggerService.class);

    private final Map<UUID, Instant> lastAccepted = new ConcurrentHashMap<>();

    private final ReforecastService reforecast;
    private final OrderRepository orders;
    private final TicketTierRepository tiers;
    private final PredictorProperties props;
    private final Clock clock;

    public ReforecastTriggerService(ReforecastService reforecast, OrderRepository orders,
                                    TicketTierRepository tiers, PredictorProperties props, Clock clock) {
        this.reforecast = reforecast;
        this.orders = orders;
        this.tiers = tiers;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Debounced recompute entry for async triggers. Leading-edge: the first call per event inside
     * the window runs; later ones are coalesced away. Returns true if it ran.
     */
    public boolean requestRecompute(UUID eventId, ReforecastTrigger trigger) {
        if (!accept(eventId)) {
            log.debug("[reforecast-trigger] coalesced {} for event {} (within debounce window)", trigger, eventId);
            return false;
        }
        try {
            reforecast.recompute(eventId, trigger);
            return true;
        } catch (Exception ex) {
            log.error("[reforecast-trigger] recompute failed for event {} ({})", eventId, trigger, ex);
            return false;
        }
    }

    private boolean accept(UUID eventId) {
        Instant now = clock.instant();
        Instant last = lastAccepted.get(eventId);
        if (last != null && Duration.between(last, now).getSeconds() < props.getReforecastDebounceSeconds()) {
            return false;
        }
        lastAccepted.put(eventId, now);
        return true;
    }

    // ---- milestone hook (fulfilment path) --------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTicketsIssued(TicketsIssuedEvent event) {
        try {
            Order order = orders.findById(event.orderId()).orElse(null);
            if (order == null || order.getEventId() == null) return;
            UUID eventId = order.getEventId();

            int capacity = tiers.sumQuantityByEventId(eventId);
            int newSold = tiers.sumSoldByEventId(eventId);
            int prevSold = reforecast.lastForecastSold(eventId);
            List<Integer> crossed = ReforecastMilestones.newlyCrossed(prevSold, newSold, capacity);
            if (crossed.isEmpty()) return; // only 25/50/75% event-level crossings warrant a milestone recompute

            log.info("[reforecast-trigger] event {} crossed {}% ({}/{}) — requesting recompute",
                    eventId, crossed, newSold, capacity);
            requestRecompute(eventId, ReforecastTrigger.MILESTONE);
        } catch (Exception ex) {
            log.warn("[reforecast-trigger] onTicketsIssued failed for order {}: {}", event.orderId(), ex.getMessage());
        }
    }

    // ---- tier-transition hook (a tier reaching 100% sell-through = it closes) ----

    /**
     * A ticket tier hitting 100% sell-through IS a tier transition (that tier closes; buyers move
     * to the next). Rides the existing {@link SalesMilestoneReachedEvent} (published AFTER_COMMIT
     * from {@code InventoryService.confirmSold}) so no new write path is needed. Only the 100%
     * crossing is a transition — 50/80% are covered by the event-level milestone hook above.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSalesMilestone(SalesMilestoneReachedEvent event) {
        try {
            if (event.threshold() != 100) return;
            TicketTier tier = tiers.findById(event.tierId()).orElse(null);
            if (tier == null || tier.getEventId() == null) return;
            log.info("[reforecast-trigger] tier {} sold out (100%) — tier transition for event {}",
                    tier.getId(), tier.getEventId());
            onTierTransition(tier.getEventId());
        } catch (Exception ex) {
            log.warn("[reforecast-trigger] onSalesMilestone failed for tier {}: {}", event.tierId(), ex.getMessage());
        }
    }

    // ---- campaign hooks (marketing send path, published AFTER_COMMIT) ------------

    /** A campaign finished sending for an event → CAMPAIGN_SEND recompute. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onCampaignSent(PredictorMarketingEvents.CampaignSent event) {
        if (event.eventId() != null) onCampaignSend(event.eventId());
    }

    /** A campaign was scheduled for an event → CAMPAIGN_SCHEDULED recompute. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onCampaignScheduledEvent(PredictorMarketingEvents.CampaignScheduled event) {
        if (event.eventId() != null) onCampaignScheduled(event.eventId());
    }

    // ---- direct hooks (call sites / listeners route through these) ---------------

    /** A ticket-tier transition activated for a live event. */
    public void onTierTransition(UUID eventId) {
        requestRecompute(eventId, ReforecastTrigger.TIER_TRANSITION);
    }

    /** A marketing campaign send completed for a live event. */
    public void onCampaignSend(UUID eventId) {
        requestRecompute(eventId, ReforecastTrigger.CAMPAIGN_SEND);
    }

    /** A marketing campaign was scheduled for a live event. */
    public void onCampaignScheduled(UUID eventId) {
        requestRecompute(eventId, ReforecastTrigger.CAMPAIGN_SCHEDULED);
    }
}
