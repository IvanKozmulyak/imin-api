package com.imin.iminapi.buyer.dto;

import java.util.List;

/**
 * {@code GET /buyer/notify-subscriptions} — the envelope, same contract as
 * {@link BuyerOrdersResponse}. See {@link BuyerSavedListResponse} for why a
 * bare array could not survive the app shipping.
 *
 * <p>Ordering is un-notified first, then newest first, and it stays in
 * {@code items} exactly as before.
 */
public record BuyerNotifySubscriptionListResponse(
        List<BuyerNotifySubscriptionResponse> items, String nextCursor) {

    public static BuyerNotifySubscriptionListResponse of(List<BuyerNotifySubscriptionResponse> items) {
        return new BuyerNotifySubscriptionListResponse(items, null);
    }
}
