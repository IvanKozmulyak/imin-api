package com.imin.iminapi.buyer.dto;

import java.util.List;

/**
 * {@code GET /buyer/saved} — the envelope, copied from
 * {@link BuyerOrdersResponse} rather than invented a second time.
 *
 * <h2>Why this is not a bare array</h2>
 *
 * <p>A top-level JSON <b>array</b> cannot grow a cursor later without becoming
 * an object, and that is a breaking change for every binary already installed —
 * which cannot be force-updated. Today the only consumer is a Vercel deploy
 * that redeploys alongside this API, so the change is free exactly once. After
 * v1.0.0 is in a store it is never free again.
 *
 * @param nextCursor always null today. The field is the point: the shape has to
 *                   exist before the app parses it, not when paging is needed.
 */
public record BuyerSavedListResponse(List<BuyerSavedResponse> items, String nextCursor) {

    public static BuyerSavedListResponse of(List<BuyerSavedResponse> items) {
        return new BuyerSavedListResponse(items, null);
    }
}
