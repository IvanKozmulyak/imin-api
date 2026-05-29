package com.imin.iminapi.service.ticket;

import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.event.InventoryService;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentListParams;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Safety net for the money-moved → tickets-issued path, the analogue of
 * {@link com.imin.iminapi.service.event.ReservationSweeper} for fulfilment.
 *
 * <p>Fulfilment is driven by the {@code payment_intent.succeeded} webhook. If that webhook is
 * permanently lost (endpoint down past Stripe's ~3-day retry window, or a dropped event), money
 * is captured but no {@link com.imin.iminapi.model.Order}/tickets are created — and the buyer's
 * held seat is meanwhile released by the ReservationSweeper. There is no DB trace pointing back
 * to the PaymentIntent in that case, so the only source of truth is Stripe. This job lists
 * recent succeeded ticket PaymentIntents and back-fills any that have no Order.
 *
 * <p>Both steps are idempotent: {@link InventoryService#confirmSold} no-ops on an already
 * confirmed/released hold, and {@link PaidCheckoutService#issuePaidOrder} no-ops when an Order
 * already exists for the PI. So a race with a late-arriving webhook is harmless.
 */
@Component
public class PaidFulfilmentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaidFulfilmentReconciler.class);

    /** How far back to scan. Comfortably beyond Stripe's ~3-day webhook retry window. */
    private static final Duration LOOKBACK = Duration.ofHours(96);
    /** Bound the work per tick. If we hit this, we log it (no silent truncation). */
    private static final int MAX_SCAN = 1000;
    private static final long PAGE_SIZE = 100L;

    private final StripeClient stripeClient;
    private final OrderRepository orders;
    private final InventoryService inventoryService;
    private final PaidCheckoutService paidCheckoutService;
    private final Clock clock;

    public PaidFulfilmentReconciler(StripeClient stripeClient,
                                    OrderRepository orders,
                                    InventoryService inventoryService,
                                    PaidCheckoutService paidCheckoutService,
                                    Clock clock) {
        this.stripeClient = stripeClient;
        this.orders = orders;
        this.inventoryService = inventoryService;
        this.paidCheckoutService = paidCheckoutService;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000) // every 15 min
    @SchedulerLock(name = "PaidFulfilmentReconciler.reconcile", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void reconcile() {
        long createdGte = clock.instant().minus(LOOKBACK).getEpochSecond();
        PaymentIntentListParams params = PaymentIntentListParams.builder()
                .setCreated(PaymentIntentListParams.Created.builder().setGte(createdGte).build())
                .setLimit(PAGE_SIZE)
                .build();

        int scanned = 0;
        int backfilled = 0;
        boolean capped = false;
        try {
            for (PaymentIntent pi : stripeClient.paymentIntents().list(params).autoPagingIterable()) {
                if (scanned >= MAX_SCAN) { capped = true; break; }
                scanned++;

                if (!"succeeded".equals(pi.getStatus())) continue;
                Map<String, String> meta = pi.getMetadata();
                if (meta == null || meta.get("reservation_id") == null) continue; // not a ticket PI
                if (orders.findByStripePaymentIntentId(pi.getId()).isPresent()) continue; // already fulfilled

                log.warn("[fulfilment-reconciler] succeeded PI {} has no Order — re-driving issuance", pi.getId());
                try {
                    UUID reservationId = UUID.fromString(meta.get("reservation_id"));
                    inventoryService.confirmSold(reservationId); // idempotent; RELEASED branch is handled
                } catch (Exception e) {
                    log.warn("[fulfilment-reconciler] confirmSold failed for PI {} — {}", pi.getId(), e.getMessage());
                }
                try {
                    paidCheckoutService.issuePaidOrder(pi); // idempotent on PI id
                    backfilled++;
                } catch (Exception e) {
                    log.error("[fulfilment-reconciler] issuePaidOrder failed for PI {} — {}",
                            pi.getId(), e.getMessage(), e);
                }
            }
        } catch (StripeException e) {
            log.error("[fulfilment-reconciler] failed to list PaymentIntents — {}", e.getMessage(), e);
        }

        if (backfilled > 0 || capped) {
            log.info("[fulfilment-reconciler] tick done scanned={} backfilled={} capped={}",
                    scanned, backfilled, capped);
            if (capped) {
                log.warn("[fulfilment-reconciler] hit MAX_SCAN={} — older unfulfilled PIs may remain "
                        + "until a later tick; investigate webhook delivery if this persists", MAX_SCAN);
            }
        }
    }
}
