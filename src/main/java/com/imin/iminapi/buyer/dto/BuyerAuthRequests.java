package com.imin.iminapi.buyer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for the unauthenticated buyer credential endpoints.
 *
 * <p>Grouped in one file because they are one API surface and each is two or
 * three fields; splitting them would be six files of ceremony.
 *
 * <h2>Password policy (§2.2)</h2>
 *
 * <p><b>Minimum 10 characters, no composition rules, no forced rotation.</b>
 * Deliberately looser than the organizer {@code SignupRequest}, which also
 * demands a letter and a digit: composition rules push people towards
 * {@code Password1} and are no longer recommended practice (NIST SP 800-63B).
 * Length is the property that matters, and the online guessing surface is closed
 * by the {@code buyer-login} bucket rather than by the alphabet.
 *
 * <p>The 128-character ceiling is not cosmetic: BCrypt silently truncates at 72
 * bytes, so accepting arbitrary-length input would quietly discard most of a
 * long passphrase. A visible limit beats a silent one.
 */
public final class BuyerAuthRequests {

    private BuyerAuthRequests() {}

    /** Email + password. {@code locale} is one of en/es/fr/uk; anything else is stored as null. */
    public record Signup(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @Size(max = 5) String locale
    ) {}

    /** The six-digit code from the address-verification mail. */
    public record VerifyEmail(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "must be six digits") String code
    ) {}

    public record ResendVerification(
            @NotBlank @Email @Size(max = 254) String email
    ) {}

    public record Login(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password
    ) {}

    public record ForgotPassword(
            @NotBlank @Email @Size(max = 254) String email
    ) {}

    public record ResetPassword(
            @NotBlank String token,
            @NotBlank @Size(min = 10, max = 128) String password
    ) {}

    /** {@code code} + signed {@code state} from Google's redirect, POSTed by the imin-public callback page. */
    public record GoogleCallback(
            @NotBlank String code,
            @NotBlank String state
    ) {}
}
