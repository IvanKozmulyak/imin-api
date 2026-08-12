package com.imin.iminapi.buyer.dto;

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
        String city,
        String locale,
        String status,
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
}
