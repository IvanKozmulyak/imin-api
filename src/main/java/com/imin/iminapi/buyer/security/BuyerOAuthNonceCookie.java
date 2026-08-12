package com.imin.iminapi.buyer.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * The browser half of the OAuth {@code state} binding (epic §2.4).
 *
 * <pre>
 * Name:     imin_oauth_nonce
 * Value:    32 random bytes, base64url
 * Path:     /api/v1/buyer/auth
 * Secure:   yes
 * HttpOnly: yes
 * SameSite: Lax
 * Max-Age:  600   (the state's own 10-minute TTL)
 * </pre>
 *
 * <p><b>Why it exists.</b> {@code OAuthStateService} binds nothing to the
 * browser: its state proves only "this platform minted a Google state in the
 * last ten minutes". That is textbook login CSRF — an attacker completes a
 * Google authorization for their own account, hands the victim the callback URL,
 * and the victim's browser lands signed in as the attacker. On the buyer side,
 * where the very next screen invites you to add an address and see its orders,
 * that is a mechanism for harvesting order history. Setting this cookie when the
 * authorize URL is handed out, and encoding only its SHA-256 into the state,
 * means a callback can only be completed by the browser that started the flow.
 *
 * <p><b>Why {@code SameSite=Lax} suffices.</b> The callback is a {@code fetch}
 * from {@code app.imin.wtf} to {@code api.imin.wtf} — cross-origin but
 * <i>same-site</i>, since both share the registrable domain {@code imin.wtf}.
 * The same reasoning as {@link BuyerSessionCookie}, and the same consequence: it
 * does not work from a {@code *.vercel.app} preview, which is not a new
 * limitation.
 *
 * <p>The cookie is cleared on callback so a state cannot be replayed by a
 * browser that still holds the nonce.
 */
public final class BuyerOAuthNonceCookie {

    public static final String NAME = "imin_oauth_nonce";
    public static final String PATH = "/api/v1/buyer/auth";
    public static final Duration MAX_AGE = Duration.ofMinutes(10);

    private BuyerOAuthNonceCookie() {}

    public static ResponseCookie issue(String nonce) {
        return base(nonce).maxAge(MAX_AGE).build();
    }

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
     * The nonce this request carries, or {@code null} when absent or
     * <b>duplicated</b>. Duplication means another host under {@code imin.wtf}
     * set a {@code Domain} cookie of this name; two candidate nonces is the same
     * problem as two candidate sessions, and gets the same answer — refuse to
     * guess, which fails the state binding closed.
     */
    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String found = null;
        for (Cookie c : cookies) {
            if (!NAME.equals(c.getName())) continue;
            if (found != null) return null;
            String v = c.getValue();
            found = (v == null || v.isBlank()) ? "" : v;
        }
        return (found == null || found.isEmpty()) ? null : found;
    }
}
