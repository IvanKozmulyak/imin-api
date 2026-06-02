package com.imin.iminapi.util;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

import java.util.Set;

/**
 * ISO 3166-1 alpha-2 codes of countries where imin can onboard a Stripe connected account
 * AND pay it out under our model. We use destination charges that transfer into the
 * connected account's Stripe balance (the {@code stripe_balance.stripe_transfers} recipient
 * capability), which Stripe only supports cross-border within one bloc: US / Canada / UK /
 * EEA / Switzerland. Our platform is EEA-based, so the supported set is the Stripe-available
 * countries inside that bloc.
 *
 * <p>Only these appear in the signup / settings country dropdown (see ReferenceController);
 * AuthService/OrgService reject anything else defensively. Countries outside the bloc (or not
 * yet listed) still hit the authoritative backstop: Stripe rejects the v2 account create and
 * StripeConnectService maps it to 422 COUNTRY_NOT_SUPPORTED.
 *
 * <p>Keep in sync with Stripe availability (https://stripe.com/global) when the bloc changes.
 * Intentionally static (compiled in) — looked up on every signup / org patch / reference fetch.
 */
public final class StripeSupportedCountries {

    public static final Set<String> CODES = Set.of(
            // EU 27
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
            "PL", "PT", "RO", "SK", "SI", "ES", "SE",
            // EEA (non-EU) where Stripe operates
            "NO", "LI",
            // Rest of the cross-border bloc Stripe supports for balance transfers
            "GB", "CH", "US", "CA",
            // UK-aligned territory Stripe lists
            "GI"
    );

    private StripeSupportedCountries() {}

    public static boolean isSupported(String iso) {
        return iso != null && CODES.contains(iso.toUpperCase());
    }

    /** Throws 422 COUNTRY_NOT_SUPPORTED when Stripe payouts aren't available for the country.
     *  No-op for null (presence is validated elsewhere, e.g. @NotBlank). */
    public static void requireSupported(String iso) {
        if (iso != null && !isSupported(iso)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.COUNTRY_NOT_SUPPORTED,
                    "Stripe payouts aren't available for organisations in this country yet.",
                    java.util.Map.of("country", "not supported"));
        }
    }
}
