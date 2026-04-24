package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventOverviewResponse(
        Metrics metrics,
        List<RecentPurchase> recentPurchases,
        PredictionDto prediction,
        List<QuickAction> quickActions) {

    public record Metrics(int sold, long revenueMinor, String currency,
                          int squadRatePct, int daysOut) {}

    public record RecentPurchase(String time, String name, String sub) {}

    public record QuickAction(String key, String icon, String label) {}
}
