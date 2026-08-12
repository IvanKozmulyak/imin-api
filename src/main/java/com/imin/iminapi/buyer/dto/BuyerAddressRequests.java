package com.imin.iminapi.buyer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for the authenticated address-set endpoints under
 * {@code /api/v1/buyer/emails} (epic §2.3).
 *
 * <p>{@code @Size(max = 254)} everywhere, matching
 * {@code buyer_account_emails.email} and {@code orders.email} — the two columns
 * these values have to join across. A longer address could never match an order
 * anyway, so rejecting it at the edge is honest rather than restrictive.
 */
public final class BuyerAddressRequests {

    private BuyerAddressRequests() {}

    /** The address to attach. Answered 204 on every branch (§2.2, §2.3 rule 5). */
    public record AddAddress(
            @NotBlank @Email @Size(max = 254) String email
    ) {}

    /** The six-digit code from the mail sent to the address being claimed. */
    public record VerifyAddress(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "must be six digits") String code
    ) {}

    /** Promote an already-verified address on this account to primary. */
    public record SetPrimary(
            @NotBlank @Email @Size(max = 254) String email
    ) {}
}
