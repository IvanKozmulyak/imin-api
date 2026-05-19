package com.imin.iminapi.dto.publicapi;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/public/events/{id}/quote}.
 *
 * <p>Validation is performed manually by {@code QuoteService} (not via bean-validation
 * annotations) so the response can be the public-contract {@code INVALID_REQUEST}
 * envelope with a populated {@code fields} map — matching the existing
 * notify/checkout shape that the buyer FE already understands.
 *
 * <p>{@code promoCode} is optional. When omitted (or null) the response simply
 * has no {@code promo} block; when supplied but invalid/expired the response
 * still returns 200 with {@code promo.applied=false} so the buyer sees the
 * cause. The only way the {@code promoCode} field forces a 400 is when it's
 * present but blank after trim (a client bug).
 */
public record QuoteRequest(
        UUID tierId,
        Integer quantity,
        String promoCode,
        // Optional per-unit price the buyer was shown. When supplied and it doesn't
        // match the current `tier.priceMinor`, the endpoint responds 409 PRICE_CHANGED
        // so the FE can refresh and re-present rather than silently quote on stale data.
        Integer expectedPriceMinor) {}
