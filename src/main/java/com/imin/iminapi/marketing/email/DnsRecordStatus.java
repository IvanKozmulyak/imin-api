package com.imin.iminapi.marketing.email;

import java.util.Locale;

/**
 * Normalized verification status of a Resend DNS record (or of a group of records that share a
 * purpose, e.g. the two SPF records). Values mirror Resend's own status vocabulary.
 *
 * <p>{@link #UNKNOWN} is the deliberate, honest fallback for any status string Resend introduces
 * that this mapping does not recognise — an unrecognised status is NEVER upgraded to
 * {@link #VERIFIED}. The whole point of this surface is that a "verified" only ever appears when
 * Resend actually said "verified".
 */
public enum DnsRecordStatus {
    VERIFIED,
    PENDING,
    NOT_STARTED,
    TEMPORARY_FAILURE,
    FAILED,
    UNKNOWN;

    /**
     * Maps a raw Resend record/domain status
     * ({@code verified}/{@code pending}/{@code not_started}/{@code failed}/{@code temporary_failure})
     * to this enum. Anything null, blank or unrecognised maps to {@link #UNKNOWN}.
     */
    public static DnsRecordStatus fromResend(String raw) {
        if (raw == null) return UNKNOWN;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "verified" -> VERIFIED;
            case "pending" -> PENDING;
            case "not_started" -> NOT_STARTED;
            case "temporary_failure" -> TEMPORARY_FAILURE;
            case "failed", "failure" -> FAILED;
            default -> UNKNOWN;
        };
    }
}
