package com.imin.iminapi.buyer.dto;

import java.util.List;

/**
 * {@code GET /buyer/organizers} — the envelope, same contract as
 * {@link BuyerOrdersResponse}. See {@link BuyerSavedListResponse} for why a
 * bare array could not survive the app shipping.
 *
 * <p>This is the cheapest of the three to change: nothing consumes it today —
 * neither imin-public nor imin-webapp calls it — so the envelope lands with no
 * frontend edit at all.
 */
public record BuyerOrganizerListResponse(
        List<BuyerPreferencesResponse.Organizer> items, String nextCursor) {

    public static BuyerOrganizerListResponse of(List<BuyerPreferencesResponse.Organizer> items) {
        return new BuyerOrganizerListResponse(items, null);
    }
}
