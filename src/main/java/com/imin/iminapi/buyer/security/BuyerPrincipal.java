package com.imin.iminapi.buyer.security;

import java.util.UUID;

/**
 * The authenticated buyer, populated by {@link BuyerSessionAuthFilter}.
 *
 * <p>Deliberately <b>not</b> a widened {@link com.imin.iminapi.security.AuthPrincipal}:
 * that record is {@code (userId, orgId, role, sessionId)} over a {@code UserRole}
 * enum and every org-scoped query in the platform derives its tenant from its
 * {@code orgId}. A buyer has no org, and giving it one — even a null one —
 * is how buyer identity leaks into organizer code paths (epic §3.1).
 *
 * <p>A buyer carries the single authority {@code ROLE_BUYER}, which is what
 * {@code SecurityConfig}'s {@code hasRole("BUYER")} matcher gates on. An
 * organizer bearer token therefore cannot read buyer data even though it is
 * "authenticated".
 */
public record BuyerPrincipal(UUID accountId, UUID sessionId) {

    public static final String ROLE = "BUYER";
    public static final String AUTHORITY = "ROLE_" + ROLE;

    /** Stable, non-null identifier for audit-log / log-line use. */
    public String actorLabel() {
        return "buyer:" + accountId;
    }
}
