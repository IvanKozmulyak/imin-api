package com.imin.iminapi.service.analytics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one rule for attributed revenue: each live order counts its total less its SUCCEEDED
 * refunds, clamped at zero as in the payout net, summed under its attribution key.
 */
public final class NetOrderRevenue {

    private NetOrderRevenue() {}

    /** Folds {@code [String key, Number totalMinor, Number succeededRefundMinor]} rows into net revenue per key. */
    public static Map<String, Long> sumByKey(List<Object[]> rows) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] r : rows) {
            long net = Math.max(0L, ((Number) r[1]).longValue() - ((Number) r[2]).longValue());
            out.merge((String) r[0], net, Long::sum);
        }
        return out;
    }
}
