package com.imin.iminapi.dto;

import java.util.Locale;

/** Per-vibe rule for whether a human hero may appear across a poster run's variants. */
public enum HumanPolicy {
    /** At least one variant must show a human. */
    REQUIRED,
    /** A human may appear (people-mode slots) but is never forced or capped. */
    OPTIONAL,
    /** At most one variant may show a human hero. */
    RARE,
    /** No variant may show a human hero. */
    FORBIDDEN;

    /** Parse the YAML token; null/unknown → REQUIRED (legacy-safe default). */
    public static HumanPolicy fromWire(String raw) {
        if (raw == null) return REQUIRED;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "optional" -> OPTIONAL;
            case "rare" -> RARE;
            case "forbidden" -> FORBIDDEN;
            case "required" -> REQUIRED;
            default -> REQUIRED;
        };
    }
}
