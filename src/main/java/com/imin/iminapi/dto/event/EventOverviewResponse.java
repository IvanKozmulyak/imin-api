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
     * @param revenueMinor         gross revenue net of refunded amounts, in minor units.
     * @param revenueAfterFeesMinor revenue minus the platform's application fee
     *                              (also netted by any refunded fee portion).
     *                              This is what lands in the organizer's payout,
     *                              excluding Stripe's processing fees.
     */
    public record Metrics(int sold,
                          int capacity,
                          long revenueMinor,
                          long revenueAfterFeesMinor,
                          String currency,
                          int daysOut) {}

    public record RecentPurchase(String time, String name, String sub) {}

    public record QuickAction(String key, String icon, String label) {}
}
