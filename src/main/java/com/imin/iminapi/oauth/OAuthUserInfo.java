package com.imin.iminapi.oauth;

/**
 * Verified identity claims extracted from a provider's ID token (plus, for
 * Apple, the first-authorization name payload). This is the seam between the
 * network-bound provider services (Google/Apple) and the pure account-linking
 * logic in {@link OAuthAccountService} — the latter is unit-tested by passing
 * an {@code OAuthUserInfo} directly, with no Google/Apple round-trip.
 *
 * @param provider      lowercase provider key ({@code "google"} / {@code "apple"})
 * @param subject       provider's stable subject id (OIDC {@code sub})
 * @param email         email claim (may be a relay address, may be blank)
 * @param emailVerified whether the provider asserts the email is verified
 * @param firstName     given name (may be blank)
 * @param lastName      family name (may be blank)
 * @param displayName   full display name if the provider supplied one, else null
 */
public record OAuthUserInfo(
        String provider,
        String subject,
        String email,
        boolean emailVerified,
        String firstName,
        String lastName,
        String displayName
) {}
