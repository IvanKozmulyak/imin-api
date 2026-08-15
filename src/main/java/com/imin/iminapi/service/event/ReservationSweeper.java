package com.imin.iminapi.service.event;

import com.imin.iminapi.model.TicketReservation;
import com.imin.iminapi.repository.TicketReservationRepository;
import com.stripe.StripeClient;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Periodically releases stale {@code HELD} reservations whose {@code expires_at}
 * is in the past. This is the safety net that makes the system robust to
 * Stripe webhook misdelivery — webhooks remain the fast path; the sweeper
 * guarantees holds drain even if a {@code checkout.session.expired} or
 * {@code payment_intent.payment_failed} never arrives.
 *
 * <p>Scheduling cadence is 60s with a per-tick batch of 200 rows. Under
 * normal load (webhooks fire) there's nothing for the sweeper to do; under
 * abnormal load (webhook outage) it drains 12k holds/hour from one replica.
 *
 * <p>{@link SchedulerLock} serializes the tick across replicas: only one
 * instance acquires the lock per cycle, so a multi-replica deploy doesn't
 * cause two sweeps to race each other. Each per-reservation release call
 * runs in its own transaction so the batch can't hold a row lock for the
 * full sweep — important under contention.
 */
@Component
public class ReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);
    private static final int BATCH_SIZE = 200;

    private final TicketReservationRepository reservations;
    private final InventoryService inventoryService;
    private final Clock clock;
    private final StripeClient stripeClient;

    public ReservationSweeper(TicketReservationRepository reservations,
                              InventoryService inventoryService,
                              Clock clock,
                              StripeClient stripeClient) {
        this.reservations = reservations;
        this.inventoryService = inventoryService;
        this.clock = clock;
        this.stripeClient = stripeClient;
    }

    /**
     * One sweeper tick. {@code fixedDelay=60_000} schedules the next run 60s
     * AFTER the current one finishes, so a slow batch can't pile up overlapping
     * ticks even if ShedLock failed. {@code initialDelay=30_000} gives the app
     * 30s after startup to settle before the first sweep, so a deployed-but-
     * not-yet-warm instance doesn't spend its first boot draining holds.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @SchedulerLock(name = "ReservationSweeper.sweep", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void sweep() {
        List<TicketReservation> stale = reservations.findHeldExpiredBefore(
                clock.instant(), PageRequest.of(0, BATCH_SIZE));
        if (stale.isEmpty()) return;

        log.info("ReservationSweeper: releasing {} expired hold(s)", stale.size());
        int released = 0;
        int skipped = 0;
        for (TicketReservation r : stale) {
            try {
                inventoryService.releaseReservation(r.getId(), "SWEEPER");
                released++;
                // Only after the seats are genuinely back in the pool — cancelling an
                // intent whose hold is still HELD would strand a buyer mid-payment.
                cancelIfNativeIntent(r);
            } catch (Exception e) {
                // One bad row shouldn't kill the batch — log and continue. The
                // row stays HELD so the next tick retries.
                log.error("ReservationSweeper: failed to release {} — {}", r.getId(), e.getMessage(), e);
                skipped++;
            }
        }
        log.info("ReservationSweeper: tick done released={} skipped={}", released, skipped);
    }

    /**
     * A released hold must also stop being payable. The hosted path gets this free
     * from the Checkout Session's {@code expires_at}, which is the same instant as
     * the reservation's. <b>A PaymentIntent has no equivalent</b>, so releasing the
     * seats without cancelling would leave a payable intent for inventory somebody
     * else can now buy — and {@link InventoryService#confirmSold} on a RELEASED row
     * deliberately credits {@code sold} anyway and logs {@code [OVERSOLD]} rather
     * than refusing, so the sale would go through, charged and transferred.
     *
     * <p>The {@code pi_} prefix is what distinguishes the two: the reservation's
     * {@code stripeSessionId} column holds whichever Stripe object owns the
     * payability window — a {@code cs_…} Session for hosted checkout, a {@code pi_…}
     * PaymentIntent for the native sheet ({@code StripePaymentIntentService} stamps
     * it through the same {@code attachSessionId} call).
     *
     * <p>Best-effort: a cancel failure must never stop the sweep. Stripe also refuses
     * to cancel an intent that already succeeded, which is correct — that one is a
     * real sale and the webhook will fulfil it.
     */
    private void cancelIfNativeIntent(TicketReservation reservation) {
        String id = reservation.getStripeSessionId();
        if (id == null || !id.startsWith("pi_")) return;
        try {
            stripeClient.paymentIntents().cancel(id);
        } catch (Exception e) {
            log.warn("Could not cancel PaymentIntent {} for released reservation {}: {}",
                    id, reservation.getId(), e.getMessage());
        }
    }
}
