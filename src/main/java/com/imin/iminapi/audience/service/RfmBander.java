package com.imin.iminapi.audience.service;

/**
 * Fixed-threshold RFM banding. Bands 0–5 (higher = better).
 * Pure function — deterministic and documented thresholds.
 *
 * Recency (R): days since last purchase (lower days = higher band)
 *   5: 0–14d   4: 15–30d   3: 31–60d   2: 61–90d   1: 91–180d   0: >180d or null
 *
 * Frequency (F): number of orders
 *   5: >=10   4: 6–9   3: 3–5   2: 2   1: 1   0: 0
 *
 * Monetary (M): spend in minor units (cents)
 *   5: >=50000   4: 20000–49999   3: 10000–19999   2: 5000–9999   1: 1–4999   0: 0
 */
public final class RfmBander {

    private RfmBander() {}

    public static short recency(Integer recencyDays) {
        if (recencyDays == null) return 0;
        if (recencyDays <= 14) return 5;
        if (recencyDays <= 30) return 4;
        if (recencyDays <= 60) return 3;
        if (recencyDays <= 90) return 2;
        if (recencyDays <= 180) return 1;
        return 0;
    }

    public static short frequency(int orders) {
        if (orders >= 10) return 5;
        if (orders >= 6)  return 4;
        if (orders >= 3)  return 3;
        if (orders == 2)  return 2;
        if (orders == 1)  return 1;
        return 0;
    }

    public static short monetary(long spendMinor) {
        if (spendMinor >= 50_000) return 5;
        if (spendMinor >= 20_000) return 4;
        if (spendMinor >= 10_000) return 3;
        if (spendMinor >= 5_000)  return 2;
        if (spendMinor >= 1)      return 1;
        return 0;
    }
}
