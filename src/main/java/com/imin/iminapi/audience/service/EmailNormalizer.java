package com.imin.iminapi.audience.service;

/**
 * Single chokepoint for email normalization.
 * normalize = lower(trim(raw)) — used as the UNIQUE key for Consumer identity.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        return raw.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
