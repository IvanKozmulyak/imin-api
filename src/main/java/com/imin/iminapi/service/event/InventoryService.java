package com.imin.iminapi.service.event;

import com.imin.iminapi.model.ReservationStatus;
import com.imin.iminapi.model.TicketReservation;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.TicketTierMilestone;
import com.imin.iminapi.repository.TicketReservationRepository;
import com.imin.iminapi.repository.TicketTierMilestoneRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Two-counter inventory bookkeeping for {@link TicketTier}, backed by a
 * first-class {@link TicketReservation} row per hold.
 *
 * <p>The {@code TicketTier.reserved} counter is derived bookkeeping kept in
 * sync with the set of HELD reservation rows for the tier. Reads of "available
 * inventory" still use the counter (single column, no aggregate scan), but
 * every transition is anchored to a reservation row so the sweeper can
 * self-heal abandoned holds even when Stripe webhooks don't fire.
 *
 * <p>Concurrency: every transition takes a pessimistic row lock on the tier
 * via {@link TicketTierRepository#findByIdForUpdate(UUID)} so concurrent
 * buyers serialize on the tier row. Status transitions on the reservation
 * itself are atomic via {@link TicketReservationRepository#markReleased}
 * /{@link TicketReservationRepository#markConfirmed} conditional updates,
 * which lets us no-op cleanly on replays / sweeper-vs-webhook races.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final TicketTierRepository tiers;
    private final TicketReservationRepository reservations;
    private final TicketTierMilestoneRepository milestones;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public InventoryService(TicketTierRepository tiers,
                            TicketReservationRepository reservations,
                            TicketTierMilestoneRepository milestones,
                            ApplicationEventPublisher publisher,
                            Clock clock) {
        this.tiers = tiers;
        this.reservations = reservations;
        this.milestones = milestones;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Hold {@code qty} seats on {@code tierId}. Increments {@code reserved} on
     * the tier AND writes a {@link TicketReservation} row in {@code HELD} state.
     * Returns the new reservation's id so the caller can stamp it onto the
     * Stripe Session metadata.
     *
     * @param expiresAt        the wall-clock instant past which the sweeper will
     *                         auto-release this hold if neither webhook fires.
     *                         For paid flows: mirror the Stripe session expires_at.
     * @param stripeSessionId  the Stripe Checkout Session id, or {@code null} for
     *                         free-checkout holds (no Stripe round-trip).
     * @throws ApiException 409 INVALID_STATE when fewer than {@code qty} seats are available.
     * @throws ApiException 404 NOT_FOUND when the tier id does not exist.
     */
    @Transactional
    public UUID reserve(UUID tierId, int qty, Instant expiresAt, String stripeSessionId) {
        TicketTier tier = tiers.findByIdForUpdate(tierId)
                .orElseThrow(() -> ApiException.notFound("TicketTier"));
        int available = tier.getQuantity() - tier.getReserved() - tier.getSold();
        if (available < qty) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Not enough tickets available",
                    Map.of("requested", String.valueOf(qty),
                            "available", String.valueOf(Math.max(available, 0))));
        }
        tier.setReserved(tier.getReserved() + qty);
        tiers.save(tier);

        TicketReservation r = new TicketReservation();
        r.setTierId(tierId);
        r.setQty(qty);
        r.setStripeSessionId(stripeSessionId);
        r.setExpiresAt(expiresAt);
        r.setStatus(ReservationStatus.HELD);
        r.setCreatedAt(clock.instant());
        r = reservations.save(r);
        return r.getId();
    }

    /**
     * Release a {@code HELD} reservation back to the pool. Idempotent: a row
     * that's already CONFIRMED/RELEASED is a no-op, and concurrent callers race
     * harmlessly via {@link TicketReservationRepository#markReleased}.
     *
     * <p>Called from three places: {@code checkout.session.expired} +
     * {@code payment_intent.payment_failed} webhooks, the {@code ReservationSweeper}
     * for expired-but-Stripe-didn't-tell-us holds, and {@link StripeCheckoutService}'s
     * rollback path when Stripe Session creation throws.
     *
     * @param reservationId  the reservation row id (from metadata or DB lookup).
     * @param reason         one of {@code WEBHOOK_EXPIRED}, {@code WEBHOOK_FAILED},
     *                       {@code SWEEPER}, {@code STRIPE_CREATE_FAILED} — recorded
     *                       on the row for forensics.
     */
    @Transactional
    public void releaseReservation(UUID reservationId, String reason) {
        Optional<TicketReservation> peek = reservations.findById(reservationId);
        if (peek.isEmpty()) {
            log.warn("releaseReservation: unknown reservation id {} (reason={})", reservationId, reason);
            return;
        }
        TicketReservation r = peek.get();
        if (r.getStatus() != ReservationStatus.HELD) {
            log.info("releaseReservation: {} already {} — no-op (reason={})",
                    reservationId, r.getStatus(), reason);
            return;
        }

        int won = reservations.markReleased(reservationId, clock.instant(), reason);
        if (won == 0) {
            // Lost the race to a concurrent releaser. Whoever won did the counter
            // decrement; we just return.
            log.info("releaseReservation: {} concurrently transitioned — no-op (reason={})",
                    reservationId, reason);
            return;
        }

        TicketTier tier = tiers.findByIdForUpdate(r.getTierId())
                .orElseThrow(() -> ApiException.notFound("TicketTier"));
        int current = tier.getReserved();
        int decrement = Math.min(current, r.getQty());
        if (r.getQty() > current) {
            log.warn("releaseReservation: reservation {} qty={} but tier {} reserved={} — clamping (drift)",
                    reservationId, r.getQty(), r.getTierId(), current);
        }
        tier.setReserved(current - decrement);
        tiers.save(tier);
        log.info("Released reservation {} qty={} on tier {} (reason={})",
                reservationId, r.getQty(), r.getTierId(), reason);
    }

    /**
     * Promote a {@code HELD} reservation to a confirmed sale. Decrements
     * {@code reserved} by the reservation's qty and increments {@code sold}.
     * Idempotent on already-CONFIRMED rows.
     *
     * <p>If the reservation was already RELEASED (e.g. sweeper raced ahead of a
     * delayed {@code payment_intent.succeeded}), we still credit {@code sold}
     * because Stripe is the source of truth for what was paid for — but
     * {@code reserved} won't decrement (it's already been). The tier ends up
     * with sold+1 / reserved unchanged, which is the correct outcome (the
     * sweeper had already given the seat away once before we learned about
     * the late payment; the organizer is now slightly over capacity and must
     * reconcile). We log this as a warning for visibility.
     */
    @Transactional
    public void confirmSold(UUID reservationId) {
        Optional<TicketReservation> peek = reservations.findById(reservationId);
        if (peek.isEmpty()) {
            log.warn("confirmSold: unknown reservation id {}", reservationId);
            return;
        }
        TicketReservation r = peek.get();
        if (r.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("confirmSold: {} already CONFIRMED — no-op", reservationId);
            return;
        }

        TicketTier tier = tiers.findByIdForUpdate(r.getTierId())
                .orElseThrow(() -> ApiException.notFound("TicketTier"));

        if (r.getStatus() == ReservationStatus.HELD) {
            int won = reservations.markConfirmed(reservationId, clock.instant());
            if (won == 0) {
                log.info("confirmSold: {} concurrently transitioned — re-checking", reservationId);
                // Re-read and fall through.
                r = reservations.findById(reservationId).orElseThrow();
                if (r.getStatus() == ReservationStatus.CONFIRMED) return;
            } else {
                int currentReserved = tier.getReserved();
                int dec = Math.min(currentReserved, r.getQty());
                if (r.getQty() > currentReserved) {
                    log.warn("confirmSold: reservation {} qty={} but tier {} reserved={} — clamping (drift)",
                            reservationId, r.getQty(), r.getTierId(), currentReserved);
                }
                tier.setReserved(currentReserved - dec);
                tier.setSold(tier.getSold() + r.getQty());
                tiers.save(tier);
                fireMilestones(tier);
                log.info("Confirmed sold qty={} on tier {} from reservation {}",
                        r.getQty(), r.getTierId(), reservationId);
                return;
            }
        }

        if (r.getStatus() == ReservationStatus.RELEASED) {
            // Sweeper / webhook released the hold before the success webhook landed.
            // Stripe is the source of truth for "money moved" — credit sold so the
            // buyer's ticket issuance proceeds, but log loudly so the organizer
            // notices the over-allocation.
            // Alertable marker: the seat was already returned to the pool (and possibly resold)
            // before this paid webhook landed. We still credit sold because Stripe is the source
            // of truth that the buyer paid — they get their ticket — but the organizer is now over
            // capacity for the tier and must reconcile (refund or honor). We deliberately do NOT
            // auto-refund here: the buyer wanted the ticket, and whether to refund vs honor is the
            // organizer's call. Monitor on "[OVERSOLD]" to surface these for manual reconciliation.
            log.error("[OVERSOLD] confirmSold: reservation {} qty={} on tier {} was already RELEASED "
                    + "before the paid webhook arrived — crediting sold without a reserved decrement; "
                    + "tier is now over capacity and needs operator reconciliation",
                    reservationId, r.getQty(), r.getTierId());
            tier.setSold(tier.getSold() + r.getQty());
            tiers.save(tier);
            fireMilestones(tier);
            // Flip the row back to CONFIRMED so a Stripe retry of the success event
            // is idempotent and so forensics show this row landed a sale.
            r.setStatus(ReservationStatus.CONFIRMED);
            r.setConfirmedAt(clock.instant());
            reservations.save(r);
        }
    }

    /**
     * Record any newly-reached 50/80/100% sell-through milestones for the tier and
     * publish a {@link SalesMilestoneReachedEvent} for the highest new one. Runs
     * inside confirmSold's transaction under the tier row lock, so the
     * exists-then-insert is race-free; the event only reaches
     * {@link SalesMilestoneNotifier} after the transaction commits. A single big
     * purchase that jumps past several thresholds records each (so none re-fire
     * later) but notifies once, on the highest.
     */
    private void fireMilestones(TicketTier tier) {
        int highest = 0;
        for (int t : SalesMilestones.satisfied(tier.getSold(), tier.getQuantity())) {
            if (!milestones.existsByTierIdAndThreshold(tier.getId(), t)) {
                TicketTierMilestone row = new TicketTierMilestone();
                row.setTierId(tier.getId());
                row.setThreshold(t);
                milestones.save(row);
                highest = t;
            }
        }
        if (highest > 0) {
            publisher.publishEvent(new SalesMilestoneReachedEvent(tier.getId(), highest));
            log.info("Sales milestone {}% reached for tier {} ({}/{} sold)",
                    highest, tier.getId(), tier.getSold(), tier.getQuantity());
        }
    }

    /**
     * Webhook fallback: legacy events stamped before the {@code reservation_id}
     * metadata existed carry only {@code tier_id}/{@code qty}/(maybe session.id).
     * Resolve via the Stripe session id and dispatch.
     *
     * <p>Returns {@code true} if a reservation was located + released, {@code false}
     * if no matching row exists (legacy pre-V27 event — treat as no-op, the tier
     * counter was already reset by the migration).
     */
    @Transactional
    public boolean releaseReservationBySessionId(String stripeSessionId, String reason) {
        if (stripeSessionId == null || stripeSessionId.isBlank()) return false;
        Optional<TicketReservation> r = reservations.findByStripeSessionId(stripeSessionId);
        if (r.isEmpty()) {
            log.info("releaseReservationBySessionId: no reservation for session {} — pre-V27 event, skipping (reason={})",
                    stripeSessionId, reason);
            return false;
        }
        releaseReservation(r.get().getId(), reason);
        return true;
    }

    /**
     * Best-effort: patch the Stripe session id onto a freshly-reserved row.
     * Called by {@link StripeCheckoutService} after the Session is created (we
     * don't know the id at reserve-time). Used for forensics and as the
     * legacy-fallback lookup path in webhook handlers.
     *
     * <p>Silently skips when the reservation is no longer HELD — it's already
     * been released or confirmed and the session id is no longer load-bearing.
     */
    @Transactional
    public void attachSessionId(UUID reservationId, String stripeSessionId) {
        if (stripeSessionId == null || stripeSessionId.isBlank()) return;
        Optional<TicketReservation> peek = reservations.findById(reservationId);
        if (peek.isEmpty()) return;
        TicketReservation r = peek.get();
        if (r.getStatus() != ReservationStatus.HELD) return;
        r.setStripeSessionId(stripeSessionId);
        reservations.save(r);
    }

    /**
     * The hold a previous request with this {@code Idempotency-Key} took, if
     * any. Backs the native checkout's replay path — see
     * {@code StripePaymentIntentService}.
     *
     * @param idempotencyKey never null or blank; a null would match the entire
     *                       unkeyed population (every web checkout ever made).
     */
    @Transactional(readOnly = true)
    public Optional<TicketReservation> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return reservations.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * Stamp an {@code Idempotency-Key} onto a freshly-reserved hold, claiming it
     * for this request.
     *
     * <p><b>The unique index is the decision, not this method.</b> The write is
     * flushed inside this transaction so a second concurrent caller with the
     * same key fails here, on
     * {@code uq_ticket_reservations_idem}, rather than at some later commit —
     * which is what lets the loser release its hold before it has asked Stripe
     * for anything. A check-then-act on
     * {@link #findByIdempotencyKey} alone would let two racing retries both take
     * seats.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException when the
     *         key is already claimed by another reservation.
     */
    @Transactional
    public void claimIdempotencyKey(UUID reservationId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        TicketReservation r = reservations.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("TicketReservation"));
        r.setIdempotencyKey(idempotencyKey);
        reservations.saveAndFlush(r);
    }

    /** Same shape as {@link #releaseReservationBySessionId} but for the success path. */
    @Transactional
    public boolean confirmSoldBySessionId(String stripeSessionId) {
        if (stripeSessionId == null || stripeSessionId.isBlank()) return false;
        Optional<TicketReservation> r = reservations.findByStripeSessionId(stripeSessionId);
        if (r.isEmpty()) {
            log.info("confirmSoldBySessionId: no reservation for session {} — pre-V27 event, skipping",
                    stripeSessionId);
            return false;
        }
        confirmSold(r.get().getId());
        return true;
    }
}
