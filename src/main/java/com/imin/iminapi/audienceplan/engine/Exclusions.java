package com.imin.iminapi.audienceplan.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why org members are not in a plan segment, as counts only. ConsentGate reasons come first, then the
 * candidate builder's own; each person is counted once, under the first reason that applies.
 */
public final class Exclusions {

    public static final String BOUGHT_THIS_EVENT = "bought_this_event";
    public static final String CONTACTED_48H = "contacted_48h";
    public static final String EVENT_CAP = "event_cap";
    public static final String MONTHLY_CAP = "monthly_cap";
    /** Mailable, but no class (no paid purchase and no proven import yet), so no class × genre segment fits. */
    public static final String NO_CLASS = "no_class";
    public static final String SMALL_GROUP = "small_group";

    /** Builder reasons in the order they are checked. */
    public static final List<String> BUILDER_REASONS =
            List.of(BOUGHT_THIS_EVENT, CONTACTED_48H, EVENT_CAP, MONTHLY_CAP, NO_CLASS, SMALL_GROUP);

    /** Sends to one person for this event at or above which they are not invited again. */
    public static final int EVENT_CAP_SENDS = 2;
    /** Sends to one person in the last 30 days at or above which they are not invited. */
    public static final int MONTHLY_CAP_SENDS = 4;
    public static final int MONTHLY_CAP_DAYS = 30;

    private Exclusions() {}

    /** Gate counts in their given order followed by every builder reason (0 when none). */
    static Map<String, Integer> merge(Map<String, Integer> consentGate, Map<String, Integer> builder) {
        Map<String, Integer> out = new LinkedHashMap<>(consentGate);
        for (String reason : BUILDER_REASONS) out.merge(reason, builder.getOrDefault(reason, 0), Integer::sum);
        return Collections.unmodifiableMap(out);
    }
}
