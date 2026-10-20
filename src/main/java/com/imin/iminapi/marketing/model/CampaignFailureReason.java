package com.imin.iminapi.marketing.model;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Why a campaign failed. {@code campaigns.last_error} stores one of the stored codes below (older rows hold free
 * text); {@link #of} maps any stored value to the closed set of wire codes, so raw text never reaches the dashboard.
 */
public final class CampaignFailureReason {

    /** Stored as-is by the sender when the campaign needs a legal identity the org lacks. */
    public static final String STORED_LEGAL_IDENTITY_MISSING = ErrorCode.ORG_LEGAL_IDENTITY_MISSING.name();
    /** The campaign's segment no longer resolves in its org. Stored and wire code. */
    public static final String SEGMENT_MISSING = "segment_missing";
    /** No row left the queue and at least one failed. Stored and wire code. */
    public static final String NOTHING_SENT = "nothing_sent";
    /** Any other failure of the drive. Stored and wire code. */
    public static final String SEND_FAILED = "send_failed";

    /** Wire code for {@link #STORED_LEGAL_IDENTITY_MISSING}. */
    public static final String LEGAL_IDENTITY_MISSING = "legal_identity_missing";

    /** Free texts written before the codes existed. */
    private static final String LEGACY_SEGMENT_NOT_FOUND = "Segment not found";
    private static final String LEGACY_NOTHING_SENT = "no recipients could be sent";

    private CampaignFailureReason() {}

    /** The code the dispatcher stores for an exception that ended a drive; the message itself is only logged. */
    public static String storedCodeFor(Exception e) {
        return e instanceof ApiException api && api.status() == HttpStatus.NOT_FOUND ? SEGMENT_MISSING : SEND_FAILED;
    }

    /** The wire code for a stored {@code last_error}, or null when none is stored. */
    public static String of(String lastError) {
        if (lastError == null || lastError.isBlank()) return null;
        String stored = lastError.trim();
        if (STORED_LEGAL_IDENTITY_MISSING.equals(stored)) return LEGAL_IDENTITY_MISSING;
        if (SEGMENT_MISSING.equals(stored) || LEGACY_SEGMENT_NOT_FOUND.equals(stored)) return SEGMENT_MISSING;
        if (NOTHING_SENT.equals(stored) || LEGACY_NOTHING_SENT.equals(stored)) return NOTHING_SENT;
        return SEND_FAILED;
    }
}
