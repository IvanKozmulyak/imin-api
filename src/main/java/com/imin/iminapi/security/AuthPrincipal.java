package com.imin.iminapi.security;

import com.imin.iminapi.model.UserRole;

import java.util.UUID;

/**
 * Authenticated caller, populated by {@link BearerTokenAuthFilter}.
 *
 * <p>There are two kinds of principal:
 * <ul>
 *   <li>{@link Kind#USER} — a human organizer, authenticated via the
 *       email-password login flow. {@code userId}, {@code orgId}, {@code role}
 *       and {@code sessionId} are all non-null.</li>
 *   <li>{@link Kind#GATE} — a shared gate-scanner device authenticated against
 *       an org-level credential issued from the organizer dashboard.
 *       {@code userId} is {@code null}; {@code role} is always
 *       {@link UserRole#MEMBER} (least-privilege placeholder — gate principals
 *       are scope-restricted in the security filter, not via roles). The
 *       {@link #actorLabel()} helper gives audit-log code a non-null
 *       identifier ({@code "gate:<orgId>"}) when the actor is a gate device.
 * </ul>
 *
 * <p>The 4-arg constructor is kept for source compatibility with existing
 * call sites (notably the test suite) and always builds a {@link Kind#USER}
 * principal.
 */
public record AuthPrincipal(UUID userId, UUID orgId, UserRole role, UUID sessionId, Kind kind) {

    public enum Kind { USER, GATE }

    /** Back-compat 4-arg constructor — always builds a USER principal. */
    public AuthPrincipal(UUID userId, UUID orgId, UserRole role, UUID sessionId) {
        this(userId, orgId, role, sessionId, Kind.USER);
    }

    public static AuthPrincipal forGate(UUID orgId, UUID sessionId) {
        // role is a placeholder — gate principals are scope-gated in the filter,
        // not via Spring Security role authorities.
        return new AuthPrincipal(null, orgId, UserRole.MEMBER, sessionId, Kind.GATE);
    }

    public boolean isGate() { return kind == Kind.GATE; }

    /**
     * Stable, non-null identifier for audit-log / log-line use. Returns the
     * user UUID as a string for human principals, or {@code "gate:<orgId>"}
     * for shared gate devices.
     */
    public String actorLabel() {
        if (kind == Kind.GATE) return "gate:" + orgId;
        return userId == null ? null : userId.toString();
    }
}
