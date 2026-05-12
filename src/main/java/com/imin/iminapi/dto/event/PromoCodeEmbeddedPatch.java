package com.imin.iminapi.dto.event;

/**
 * One promo code as it arrives inside an embedded PATCH /events/:id body.
 *
 * The wizard owns the entire promo list — it sends the canonical set on each save,
 * and the server replaces what it has. There's no id: codes are unique per event,
 * so the natural key is (event_id, code). Since {@link com.imin.iminapi.service.event.EventService#patch}
 * only accepts drafts (it 409s on non-drafts), reconciling by full replacement is
 * safe: a draft can't have any redemption history to preserve.
 */
public record PromoCodeEmbeddedPatch(
        String code,
        Integer discountPct,
        Integer maxUses
) {}
