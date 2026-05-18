package com.imin.iminapi.dto.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of {@code POST /api/v1/public/events/{id}/quote}.
 *
 * <p>All monetary fields are in the smallest currency unit (cents for EUR, etc.).
 * {@code feeMinor} is always {@code 0} in the current platform — the application
 * fee Stripe routes to the platform comes out of the organizer's net, not as a
 * buyer-visible surcharge. Field kept in the contract so adding a buyer fee
 * later doesn't require a payload migration.
 *
 * <p>{@code promo} is present only when the request carried a {@code promoCode}.
 * On a successfully applied code: {@code applied=true}, {@code reason=null}.
 * On a code that exists in the form but isn't usable (unknown, disabled,
 * exhausted): {@code applied=false}, {@code discountPct=0}, and {@code reason}
 * carries a human-readable explanation suitable for surfacing inline.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteResponse(
        String currency,
        int unitPriceMinor,
        long subtotalMinor,
        long discountMinor,
        long feeMinor,
        long totalMinor,
        Promo promo) {

    public record Promo(
            boolean applied,
            String code,
            int discountPct,
            String reason) {}
}
