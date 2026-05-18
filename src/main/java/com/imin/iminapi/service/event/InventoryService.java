package com.imin.iminapi.service.event;

import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Two-counter inventory bookkeeping for {@link TicketTier}.
 *
 * <p>Each tier carries a {@code quantity} cap, a {@code reserved} counter (holds taken
 * by in-flight checkout sessions) and a {@code sold} counter (fulfilled purchases). The
 * available count visible to new buyers is {@code quantity - reserved - sold}.
 *
 * <p>All three methods take a pessimistic row lock via
 * {@link TicketTierRepository#findByIdForUpdate(UUID)} so concurrent buyers serialize on
 * the tier row — otherwise two checkouts could both pass the capacity check on the same
 * remaining seat. The lock is released when the surrounding transaction commits.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final TicketTierRepository tiers;

    public InventoryService(TicketTierRepository tiers) {
        this.tiers = tiers;
    }

    /**
     * Hold {@code qty} seats on {@code tierId}. Increments {@code reserved}.
     *
     * @throws ApiException 409 INVALID_STATE when fewer than {@code qty} seats are available.
     * @throws ApiException 404 NOT_FOUND when the tier id does not exist.
     */
    @Transactional
    public void reserve(UUID tierId, int qty) {
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
    }

    /**
     * Release a previously-held reservation (e.g. checkout abandoned or expired).
     * Decrements {@code reserved} by at most its current value — never goes negative.
     *
     * <p>If the requested decrement would drive {@code reserved} below zero, we clamp
     * at zero and log a warning: this indicates a double-release bug (the same hold
     * was released twice) but we'd rather drop the second decrement than corrupt the
     * counter.
     */
    @Transactional
    public void releaseReservation(UUID tierId, int qty) {
        TicketTier tier = tiers.findByIdForUpdate(tierId)
                .orElseThrow(() -> ApiException.notFound("TicketTier"));
        int current = tier.getReserved();
        if (qty > current) {
            log.warn("releaseReservation: requested {} but only {} reserved on tier {} — clamping to 0 (possible double-release)",
                    qty, current, tierId);
        }
        int decrement = Math.min(current, qty);
        tier.setReserved(current - decrement);
        tiers.save(tier);
    }

    /**
     * Promote a reservation to a confirmed sale. Decrements {@code reserved} by
     * {@code min(reserved, qty)} and unconditionally increments {@code sold} by {@code qty}.
     *
     * <p>Same clamp/log behavior as {@link #releaseReservation} for the reserved counter.
     * The {@code sold} counter is always advanced by the full {@code qty} because Stripe
     * is the source of truth for what was paid for; if the reservation was somehow already
     * released we still owe the buyer their tickets.
     */
    @Transactional
    public void confirmSold(UUID tierId, int qty) {
        TicketTier tier = tiers.findByIdForUpdate(tierId)
                .orElseThrow(() -> ApiException.notFound("TicketTier"));
        int current = tier.getReserved();
        if (qty > current) {
            log.warn("confirmSold: requested {} but only {} reserved on tier {} — clamping reserved decrement to 0 (possible double-release)",
                    qty, current, tierId);
        }
        int decrement = Math.min(current, qty);
        tier.setReserved(current - decrement);
        tier.setSold(tier.getSold() + qty);
        tiers.save(tier);
    }
}
