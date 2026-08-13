package com.imin.iminapi.buyer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request and response bodies for the profile writes (spec §4.3).
 *
 * <p>{@code PATCH /buyer/me} is deliberately absent from this file: a partial
 * patch has to tell an <i>absent</i> key from an <i>explicit null</i>, and a
 * record cannot express that difference — {@code city: null} and "city
 * untouched" would deserialize identically. It is taken as a
 * {@code Map<String, Object>} in {@code BuyerProfileController} and branched on
 * {@code containsKey}.
 */
public final class BuyerProfileRequests {

    private BuyerProfileRequests() {}

    /**
     * {@code POST /buyer/me/password}.
     *
     * <p>{@code currentPassword} is nullable on purpose: a Google-only account
     * has no {@code password_hash} and therefore nothing to prove. The service
     * requires it exactly when one exists.
     *
     * <p>The {@code newPassword} bounds match {@code BuyerAuthRequests} exactly
     * — 10 minimum, and 128 because BCrypt silently truncates at 72. Loosening
     * them here would let an account end up with a password it could never have
     * signed up with.
     */
    public record PasswordChangeRequest(
            String currentPassword,
            @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    /** One row of {@code GET /buyer/identities}. Never carries the provider user id. */
    public record IdentityResponse(String provider, String emailAtLink, Instant createdAt) {}
}
