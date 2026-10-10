package com.imin.iminapi.service.analytics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one rule for attributed revenue: each live order counts its total less its SUCCEEDED
 * refunds and its LOST chargebacks, clamped at zero as in the payout net, summed under its key.
 * OPEN chargebacks are money at risk, not gone, so they stay in.
 */
public final class NetOrderRevenue {

    private NetOrderRevenue() {}

    /**
     * Folds {@code [String key, Number totalMinor, Number succeededRefundMinor, Number lostDisputeMinor]}
     * rows into net revenue per key.
     */
    public static Map<String, Long> sumByKey(List<Object[]> rows) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] r : rows) {
            long net = Math.max(0L, num(r[1]) - num(r[2]) - num(r[3]));
            out.merge((String) r[0], net, Long::sum);
        }
        return out;
    }

    private static long num(Object v) {
        return ((Number) v).longValue();
    }
}
