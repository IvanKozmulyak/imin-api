package com.imin.iminapi.security;

import com.imin.iminapi.model.UserRole;

/**
 * The one place that answers "is this caller senior enough to do that".
 *
 * <p>Before this existed the backend had exactly three role checks
 * ({@code OrgService.delete}, {@code TeamService.remove}'s target-is-OWNER
 * refusal, and the DSAR gate) and {@code SecurityConfig} gated the whole
 * organizer surface on {@code .authenticated()} alone — so the {@code role}
 * column was carried on the principal, rendered in the dashboard, and consulted
 * almost nowhere. Any MEMBER could mint an ADMIN via the team invite, export
 * every attendee's address, rotate the door credential and edit the org's
 * Stripe jurisdiction.
 *
 * <h2>403, not 404</h2>
 *
 * <p>Cross-<i>org</i> access answers the leak-safe {@code 404 NOT_FOUND} that
 * {@code CrossOrgScopingTest} pins, because confirming the resource exists is
 * itself the leak. A within-org role refusal is the opposite case: the resource
 * is the caller's own org, they already know it exists, and telling them
 * "your role cannot do this" is the only answer that lets the dashboard show a
 * useful message. So this throws {@code 403 FORBIDDEN} — the same shape
 * {@code OrgService.delete} has always used for the owner-only org delete.
 *
 * <h2>Ranking</h2>
 *
 * <p>{@link UserRole} is declared OWNER, ADMIN, MEMBER, so seniority runs
 * <i>down</i> the ordinals; {@link #rank(UserRole)} inverts that rather than
 * relying on a reader remembering the declaration order. A GATE principal
 * carries a placeholder {@code MEMBER} role (see {@link AuthPrincipal}) and is
 * treated as strictly below MEMBER: a door scanner is a shared device, not a
 * person, and nothing here is ever something it should do.
 */
public final class RoleGuard {

    private RoleGuard() {}

    /** OWNER = 2, ADMIN = 1, MEMBER = 0. Null (or a gate device) is below all of them. */
    private static int rank(UserRole role) {
        if (role == null) return -1;
        return switch (role) {
            case OWNER -> 2;
            case ADMIN -> 1;
            case MEMBER -> 0;
        };
    }

    private static int rankOf(AuthPrincipal p) {
        if (p == null || p.isGate()) return -1;
        return rank(p.role());
    }

    /** True when the caller's role is {@code min} or more senior. */
    public static boolean isAtLeast(AuthPrincipal p, UserRole min) {
        return rankOf(p) >= rank(min);
    }

    /**
     * Refuses the call unless the caller is {@code min} or more senior.
     *
     * @param action human-readable, used in the 403 message ("export attendees")
     */
    public static void requireAtLeast(AuthPrincipal p, UserRole min, String action) {
        if (isAtLeast(p, min)) return;
        throw ApiException.forbidden("Your role cannot " + action);
    }

    /**
     * Refuses granting a role more senior than the caller's own. Without this an
     * ADMIN could invite an OWNER and an org would grow a peer nobody approved;
     * with {@link #requireAtLeast} in front of it, a MEMBER cannot grant at all.
     */
    public static void requireCanGrant(AuthPrincipal p, UserRole granted, String action) {
        if (rankOf(p) >= rank(granted)) return;
        throw ApiException.forbidden("Your role cannot " + action + " with the role " + granted.wireValue());
    }
}
