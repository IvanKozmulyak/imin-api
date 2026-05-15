package com.imin.iminapi.util;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

import java.util.Set;

/**
 * ISO 3166-1 alpha-2 codes of countries we refuse to serve due to sanctions / payment-network
 * unavailability. Covers OFAC comprehensive sanctions (CU, IR, KP, SY) plus Stripe-unsupported
 * territories (RU, BY) — Stripe Connect won't onboard accounts in these jurisdictions, so
 * letting an org pick one would just dead-end at /stripe/connect anyway.
 *
 * <p>Refresh this list when the regulatory picture changes; it's intentionally static (compiled
 * into the jar) rather than DB-backed because the lookup is on every signup / login / org patch
 * and the list almost never moves.
 */
public final class SanctionedCountries {

    public static final Set<String> CODES = Set.of(
            "CU",   // Cuba
            "IR",   // Iran
            "KP",   // North Korea (DPRK)
            "SY",   // Syria
            "RU",   // Russia (Stripe Connect unsupported)
            "BY"    // Belarus (Stripe Connect unsupported)
    );

    private SanctionedCountries() {}

    public static boolean isSanctioned(String iso) {
        return iso != null && CODES.contains(iso.toUpperCase());
    }

    /**
     * Throws 422 COUNTRY_NOT_ALLOWED if the given country is on the sanctions list.
     * No-op for any other value (including null, which signup/patch handle elsewhere).
     */
    public static void requireAllowed(String iso) {
        if (isSanctioned(iso)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.COUNTRY_NOT_ALLOWED,
                    "We can't serve organisations in this country.",
                    java.util.Map.of("country", "not allowed"));
        }
    }
}
