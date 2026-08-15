package com.imin.iminapi.buyer.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one place {@code X-Imin-Client: native} is read.
 *
 * <p>A native client cannot hold an {@code HttpOnly} cookie, so it needs the raw
 * session token and a way to say so. This header is that signal, and it is
 * deliberately opt-in: a browser never sends it, so the web response shape is
 * unchanged.
 *
 * <h2>Why the header is also a CSRF defence</h2>
 *
 * <p>{@link BuyerRequestGuardFilter}'s {@code Origin} check exists because a
 * cookie became a credential — browsers attach cookies to cross-site requests
 * on their own. A browser cannot attach a <i>custom header</i> cross-site
 * without a CORS preflight, and the buyer CORS config is an exact-origin
 * allowlist that denies an attacker's preflight. So a request carrying this
 * header and <b>no session cookie</b> cannot be a cross-site forgery, which is
 * exactly the OWASP custom-request-header pattern.
 *
 * <p>The "no cookie" half is load-bearing and must not be dropped: a request
 * that carries a cookie is CSRF-able no matter what headers it also carries, so
 * it keeps the full guard.
 */
public final class BuyerClientKind {

    public static final String HEADER = "X-Imin-Client";
    public static final String NATIVE = "native";

    private BuyerClientKind() {}

    /** True when the caller declared itself native. Case-insensitive, trimmed. */
    public static boolean isNative(HttpServletRequest request) {
        String value = request.getHeader(HEADER);
        return value != null && NATIVE.equalsIgnoreCase(value.trim());
    }

    /**
     * True when this request may skip the cookie-era CSRF guard: it declared
     * itself native AND carries no session cookie.
     */
    public static boolean isCookielessNative(HttpServletRequest request) {
        return isNative(request) && BuyerSessionCookie.read(request) == null;
    }

    /**
     * True when this request may be answered with the <b>raw session token</b>
     * in the response body.
     *
     * <p>Deliberately stricter than {@link #isNative}. The buyer CORS config
     * registers {@code app.imin.wtf} with {@code allowedHeaders("*")}, so page
     * JavaScript on the buyer site can set {@code X-Imin-Client: native} on any
     * request it makes — hooking {@code fetch} on the sign-in screen would turn
     * an ordinary password login into a response carrying a portable
     * <b>180-day</b> credential, usable off-device with no cookie, no
     * {@code Origin} and no {@code SameSite} constraint. That is a real
     * escalation even though script execution on that origin is already bad
     * news, because the token outlives the page.
     *
     * <p>So emission additionally requires the absence of an {@code Origin}
     * header. Browsers set {@code Origin} on every cross-origin request and on
     * every same-origin state-changing one; a native HTTP client sets none.
     * A page therefore cannot construct a request that satisfies this, whatever
     * headers it adds.
     */
    public static boolean mayReceiveRawToken(HttpServletRequest request) {
        return isCookielessNative(request)
                && request.getHeader(org.springframework.http.HttpHeaders.ORIGIN) == null;
    }
}
