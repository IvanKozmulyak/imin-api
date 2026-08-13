package com.imin.iminapi.buyer.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * The one place the buyer session cookie is described (epic §2.5):
 *
 * <pre>
 * Name:     imin_buyer_session
 * Value:    the raw 32-byte base64url session token (never the account id)
 * Domain:   unset — host-only on api.imin.wtf
 * Path:     /api/v1/buyer
 * Secure:   yes
 * HttpOnly: yes
 * SameSite: Lax
 * Max-Age:  15552000 (180 days)
 * </pre>
 *
 * <p><b>Host-only, not {@code Domain=.imin.wtf}</b>: a domain cookie would be
 * sent to {@code dashboard.imin.wtf} too, handing the organizer dashboard a
 * buyer credential on every request.
 *
 * <p><b>{@code SameSite=Lax}, not {@code None}</b>: {@code app.imin.wtf} and
 * {@code api.imin.wtf} share the registrable domain {@code imin.wtf}, so calls
 * between them are same-site (cross-origin, but same-site) and a Lax cookie
 * rides along. {@code None} would be a strict downgrade — it would also send
 * the cookie from genuinely foreign sites, and it is what would turn the
 * wildcard {@code https://imin-public-*.vercel.app} CORS pattern into an
 * exploitable credentialed origin. There is deliberately no staging escape
 * hatch for this (§2.6): ephemeral per-commit previews cannot test signed-in
 * flows, and that is the correct trade.
 *
 * <p>{@code Secure} is unconditional. Browsers treat {@code http://localhost}
 * as a secure context, so local development still works.
 *
 * <h2>Cookie shadowing, and why not {@code __Host-}</h2>
 *
 * <p>Host-only stops the cookie being <i>sent</i> to other {@code *.imin.wtf}
 * hosts, but it does not stop one of them <i>setting</i> a same-named cookie:
 * an XSS or a subdomain takeover anywhere under {@code imin.wtf} can write
 * {@code Domain=imin.wtf; imin_buyer_session=…}, which the browser will then
 * also send to {@code api.imin.wtf}. The request then carries two cookies of
 * this name and the RFC 6265 ordering rules do not guarantee which one
 * {@code getCookies()} yields first — an attacker-chosen session could win.
 *
 * <p>The textbook fix is the {@code __Host-} name prefix, which browsers only
 * accept on a cookie that is {@code Secure}, has no {@code Domain} and has
 * {@code Path=/}. That last requirement is incompatible with the
 * {@code Path=/api/v1/buyer} the spec chose (§2.5), and widening the path would
 * attach the buyer credential to every organizer endpoint, every Stripe webhook
 * and every public route on the API host. We keep the narrow path — it is the
 * property that keeps the credential where it is used — and defend the
 * shadowing case directly instead: {@link #read(HttpServletRequest)} returns
 * {@code null} when more than one cookie of this name is present, so a shadowed
 * request authenticates as nobody rather than as whoever won the ordering.
 * Revisit if the path ever becomes {@code /}: at that point {@code __Host-} is
 * strictly better and free.
 */
public final class BuyerSessionCookie {

    public static final String NAME = "imin_buyer_session";
    public static final String PATH = "/api/v1/buyer";

    private BuyerSessionCookie() {}

    public static ResponseCookie issue(String rawToken, Duration maxAge) {
        return base(rawToken).maxAge(maxAge).build();
    }

    /** Same attributes, empty value, Max-Age 0 — the only way a browser drops it. */
    public static ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .path(PATH)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax");
    }

    /**
     * The raw cookie value, or {@code null} when it is absent, blank, or
     * <b>duplicated</b>. Duplication is the shadowing case described above: a
     * second cookie of this name can only have come from a {@code Domain}
     * cookie set by another host under {@code imin.wtf}, so the safe reading of
     * "two candidate credentials" is "no credential".
     */
    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String found = null;
        for (Cookie c : cookies) {
            if (!NAME.equals(c.getName())) continue;
            if (found != null) return null; // shadowed — refuse to guess
            String v = c.getValue();
            found = (v == null || v.isBlank()) ? "" : v;
        }
        return (found == null || found.isEmpty()) ? null : found;
    }

    /** True when the request carries more than one cookie of this name. */
    public static boolean isShadowed(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        int count = 0;
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName()) && ++count > 1) return true;
        }
        return false;
    }
}
