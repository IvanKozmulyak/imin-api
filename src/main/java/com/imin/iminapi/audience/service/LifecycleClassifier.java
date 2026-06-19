package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Membership;

/**
 * Pure function: 7-state lifecycle classification.
 * Input is the current membership projection; output is one of the 7 string keys.
 * wonback takes precedence over lapsing/dormant for re-engaged former dormants.
 */
public final class LifecycleClassifier {

    private LifecycleClassifier() {}

    public static final String PROSPECT   = "prospect";
    public static final String FIRSTTIME  = "firsttime";
    public static final String REPEAT     = "repeat";
    public static final String VIP        = "vip";
    public static final String LAPSING    = "lapsing";
    public static final String DORMANT    = "dormant";
    public static final String WONBACK    = "wonback";

    /**
     * Classify purely from the membership aggregate — no DB calls.
     *
     * <p>Priority order (highest wins):
     * 1. prospect  — never attended an event
     * 2. vip       — big spender & loyal
     * 3. wonback   — purchased recently after a dormant gap (recency < 90 and events >= 2 and prior dormancy)
     * 4. dormant   — recency >= 180
     * 5. lapsing   — recency >= 90
     * 6. repeat    — events >= 2
     * 7. firsttime — events == 1
     */
    public static String classify(Membership m) {
        int events = m.getEvents();
        long spend = m.getSpendMinor();
        Integer recency = m.getRecencyDays();

        if (events == 0) return PROSPECT;

        boolean dormantGap = recency != null && recency >= 180;
        boolean lapsingGap = recency != null && recency >= 90;
        boolean recentlyActive = recency != null && recency < 90;

        if (spend >= 20_000 && events >= 4) return VIP;

        // wonback: recently purchased after previously being dormant
        // We approximate: events >= 2 and currently recent but was dormant (we infer from lifecycle history
        // not available here, so we use: events >= 2 and recency < 60 — conservative signal)
        // NOTE: full wonback detection would require history; we approximate from state only.
        if (events >= 2 && recentlyActive && m.getLifecycle() != null
                && (DORMANT.equals(m.getLifecycle()) || LAPSING.equals(m.getLifecycle()))) {
            return WONBACK;
        }

        if (dormantGap) return DORMANT;
        if (lapsingGap) return LAPSING;
        if (events >= 2) return REPEAT;
        return FIRSTTIME;
    }
}
