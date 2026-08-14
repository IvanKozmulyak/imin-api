package com.imin.iminapi.buyer.dto;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The signed-in buyer, as {@code GET /buyer/me} returns it.
 *
 * <p>{@code imin-public} calls this on mount to resolve the nav — the session
 * cookie is host-only on the API origin, so the Next server cannot see it and
 * cannot render signed-in chrome at SSR (epic §2.5).
 *
 * <p>Addresses are included from R1.1 because the table ships in V83 and the
 * profile screen needs them; preferences arrive with V85 in a later slice and
 * are deliberately absent rather than stubbed with defaults that do not exist
 * in the database.
 */
public record BuyerMeResponse(
        UUID id,
        String displayName,
        String firstName,
        String lastName,
        java.time.LocalDate dateOfBirth,
        String city,
        String locale,
        String status,
        /**
         * When the buyer accepted the terms, or null.
         *
         * <p>Exposed because the frontend routes on it: a session whose account
         * has never accepted is sent to the finish-registration step. Null means
         * "never recorded" — accounts predate the step — and not "declined".
         */
        java.time.Instant termsAcceptedAt,
        Instant deleteAt,
        Instant createdAt,
        List<Address> emails) {

    /**
     * One address on the account. {@code verified} is the only field that
     * grants anything — an unverified row is a claim, not a permission.
     */
    public record Address(
            String email,
            boolean primary,
            boolean verified,
            String addedVia,
            Instant createdAt) {}

    /**
     * The single projection used by {@code GET /buyer/me} and by every endpoint
     * that signs a buyer in (verify-email, login, the Google callback), so those
     * four responses cannot drift into three different shapes.
     */
    public static BuyerMeResponse of(BuyerAccount account, List<BuyerAccountEmail> addresses) {
        return new BuyerMeResponse(
                account.getId(),
                account.getDisplayName(),
                account.getFirstName(),
                account.getLastName(),
                account.getDateOfBirth(),
                account.getCity(),
                account.getLocale(),
                account.getStatus(),
                account.getTermsAcceptedAt(),
                account.getDeleteAt(),
                account.getCreatedAt(),
                addresses.stream().map(BuyerMeResponse::toAddress).toList());
    }

    /**
     * The address list on its own, for {@code GET /buyer/emails}. Same
     * projection as the {@code emails} block above so the profile screen and the
     * nav cannot disagree about what "verified" means.
     */
    public static List<Address> addresses(List<BuyerAccountEmail> rows) {
        return rows.stream().map(BuyerMeResponse::toAddress).toList();
    }

    private static Address toAddress(BuyerAccountEmail row) {
        return new Address(row.getEmail(), row.isPrimary(), row.isVerified(),
                row.getAddedVia(), row.getCreatedAt());
    }
}
