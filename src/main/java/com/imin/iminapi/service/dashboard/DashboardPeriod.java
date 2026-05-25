package com.imin.iminapi.service.dashboard;

import java.time.Duration;

/**
 * Time window for dashboard cycle/business cards. {@code ALL} means "since
 * org inception" — deltas are not applicable in that case.
 */
public enum DashboardPeriod {
    D7("7d", Duration.ofDays(7)),
    D30("30d", Duration.ofDays(30)),
    D90("90d", Duration.ofDays(90)),
    ALL("all", null);

    private final String wire;
    private final Duration duration;

    DashboardPeriod(String wire, Duration duration) {
        this.wire = wire;
        this.duration = duration;
    }

    public String wire() { return wire; }

    /** Null for {@link #ALL}. */
    public Duration duration() { return duration; }

    public boolean isAll() { return this == ALL; }

    /** Lenient parse — falls back to {@link #D30} on unknown input. */
    public static DashboardPeriod parse(String s) {
        if (s == null) return D30;
        return switch (s.toLowerCase()) {
            case "7d" -> D7;
            case "30d" -> D30;
            case "90d" -> D90;
            case "all" -> ALL;
            default -> D30;
        };
    }
}
