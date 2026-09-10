package com.imin.iminapi.audience.service;

/**
 * The seven system-provisioned segments, keyed by a stable identifier rather than by the
 * display name.
 *
 * <p>Segment resolution used to {@code switch} on {@code segment.getName()}, so any segment
 * an organizer or the AI namer happened to call "VIP" was silently resolved with the
 * prebuilt VIP query instead of its own rules — and the campaign built from it mailed a
 * different list than the one the dashboard displayed. The key here is written to
 * {@code segments.prebuilt_key} when the set is provisioned (V115 backfills the existing
 * rows) and is what {@code SegmentService} routes on. A segment with no key evaluates its
 * own {@code rules_json}, whatever it is called.
 *
 * <p>The rules JSON is the wire/UI description of each prebuilt predicate and is kept
 * equivalent to the matching {@code MembershipRepository} query, which is what actually
 * runs — the indexed query is the reason prebuilts do not go through the in-Java engine.
 */
public enum PrebuiltSegment {

    REPEAT("Repeat",
            "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"}]"),
    VIP("VIP",
            "[{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"20000\"},{\"field\":\"events\",\"operator\":\">=\",\"value\":\"4\"}]"),
    LAPSED("Lapsed",
            "[{\"field\":\"recency\",\"operator\":\">=\",\"value\":\"90\"},{\"field\":\"consent_status\",\"operator\":\"==\",\"value\":\"subscribed\"}]"),
    FIRST_TIMERS("First-timers",
            "[{\"field\":\"events\",\"operator\":\"==\",\"value\":\"1\"}]"),
    PROMOTERS("Promoters",
            "[{\"field\":\"nps\",\"operator\":\">=\",\"value\":\"9\"}]"),
    BOUGHT_NO_SHOWED("Bought-no-showed",
            "[{\"field\":\"no_show\",\"operator\":\">\",\"value\":\"0\"}]"),
    NEWEST_30D("Newest-30d",
            "[{\"field\":\"recency\",\"operator\":\"<=\",\"value\":\"30\"},{\"field\":\"events\",\"operator\":\"<=\",\"value\":\"1\"}]");

    private final String displayName;
    private final String rulesJson;

    PrebuiltSegment(String displayName, String rulesJson) {
        this.displayName = displayName;
        this.rulesJson = rulesJson;
    }

    /** The name the segment is provisioned with. Display only — never routed on. */
    public String displayName() {
        return displayName;
    }

    public String rulesJson() {
        return rulesJson;
    }

    /** The value stored in {@code segments.prebuilt_key}. */
    public String key() {
        return name();
    }

    /** The prebuilt this key names, or null for a custom segment (and for an unknown key). */
    public static PrebuiltSegment byKey(String key) {
        if (key == null || key.isBlank()) return null;
        for (PrebuiltSegment p : values()) {
            if (p.name().equals(key)) return p;
        }
        return null;
    }
}
