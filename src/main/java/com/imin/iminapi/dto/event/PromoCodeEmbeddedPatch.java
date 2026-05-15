package com.imin.iminapi.dto.event;

/**
 * One promo code as it arrives inside an embedded PATCH /events/:id body.
 *
 * The wizard owns the entire promo list — it sends the canonical set on each save,
 * and the server reconciles what it has. There's no id: codes are unique per event,
 * so the natural key is (event_id, code). Reconciliation upserts by code, so existing
 * codes keep their {@code usedCount} and id when the wizard edits a live event;
 * codes absent from the patch are deleted.
 */
public record PromoCodeEmbeddedPatch(
        String code,
        Integer discountPct,
        Integer maxUses
) {}
