package com.imin.iminapi.dto.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of {@code POST /api/v1/public/events/{id}/quote}.
 *
 * <p>All monetary fields are in the smallest currency unit (cents for EUR, etc.).
 *
 * <p>{@code feeMinor} is the <b>buyer-visible service fee</b>: {@code 5% of subtotal}
 * plus {@code €0.99 per ticket} ({@link com.imin.iminapi.stripe.StripeProperties}
 * {@code applicationFeeBps=500}, {@code applicationFeeFixedMinor=99}). It is computed on
 * the <b>pre-discount</b> {@code subtotalMinor}, so a partial promo shrinks what the
 * organizer nets, not the platform's cut. {@code totalMinor = subtotal - discount + fee}
 * is what the buyer is actually charged.
 *
 * <p>The fee is <b>waived entirely when the net total is zero</b> — a free tier
 * ({@code unitPriceMinor == 0}) or a 100%-off promo on a paid tier. Both quote
 * {@code feeMinor=0, totalMinor=0} and take the free checkout path (no Stripe session).
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
