package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventOverviewResponse(
        Metrics metrics,
        List<RecentPurchase> recentPurchases,
        PredictionDto prediction,
        List<QuickAction> quickActions) {

    /**
     * Headline figures, all three of which are NET of chargebacks: an order with an OPEN or
     * LOST dispute is money the organizer does not have, so counting it would be a claim we
     * cannot back. {@code disputedCount} / {@code disputedMinor} say how much was taken off.
     *
     * @param revenueMinor          gross revenue net of refunded and disputed amounts, in minor units.
     * @param revenueAfterFeesMinor revenue minus the platform's application fee
     *                              (also netted by any refunded fee portion).
     *                              This is what lands in the organizer's payout,
     *                              excluding Stripe's processing fees.
     * @param disputedCount         charged-back ORDERS excluded from {@code sold}, not tickets.
     * @param disputedMinor         face value withheld by those chargebacks, in minor units.
     */
    public record Metrics(int sold,
                          int capacity,
                          long revenueMinor,
                          long revenueAfterFeesMinor,
                          String currency,
                          int daysOut,
                          int disputedCount,
                          long disputedMinor) {}

    public record RecentPurchase(String time, String name, String sub) {}

    public record QuickAction(String key, String icon, String label) {}
}
