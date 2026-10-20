package com.imin.iminapi.service.auth;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;

/** The organizer-terms versions a sign-up may accept; the stored version is the matched id, not the client's text. */
public final class OrganizerTerms {

    private OrganizerTerms() {}

    /** The current terms id; during a release the accepted set also holds the next id the dashboard may send. */
    public static final String CURRENT_VERSION = "1.0";

    // Bump order: add the new id here and ship the API, then ship the dashboard, then drop the old id.
    private static final Set<String> ACCEPTED_VERSIONS = Set.of(CURRENT_VERSION, "1.1");

    /** The {@code termsVersion} field message for a blank or unknown version. */
    public static final String STALE_MESSAGE = "The organizer terms have changed; accept the current version";

    /** The accepted version the request names, trimmed; 400 FIELD_INVALID on {@code termsVersion} otherwise. */
    public static String accepted(String version) {
        String trimmed = version == null ? "" : version.trim();
        if (!ACCEPTED_VERSIONS.contains(trimmed)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                    Map.of("termsVersion", STALE_MESSAGE));
        }
        return trimmed;
    }
}
