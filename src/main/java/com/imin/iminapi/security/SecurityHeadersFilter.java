package com.imin.iminapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Response hardening headers. The API shipped with none of these, on an origin
 * where {@code GET /api/v1/public/orders/{token}} returns a buyer's address and
 * their ticket QR codes.
 *
 * <h2>What each one is for here</h2>
 *
 * <ul>
 *   <li><b>Referrer-Policy</b> — order and ticket tokens travel in the URL path.
 *       A full referrer would hand that token to any third party a page links
 *       out to, which on this API means handing over the ticket.</li>
 *   <li><b>X-Content-Type-Options</b> — the attendee CSV export and the QR PNG
 *       are attacker-influenced bytes served from our origin; sniffing one into
 *       HTML is the whole point of the header.</li>
 *   <li><b>Permissions-Policy</b> — nothing this API serves has any business
 *       asking for a camera, a microphone or a location, so all three are denied
 *       outright rather than left to the browser default.</li>
 *   <li><b>Content-Security-Policy</b> — {@code default-src 'none';
 *       frame-ancestors 'none'} on {@code /api/**}. JSON needs no sources at all,
 *       so the tightest possible policy costs nothing, and {@code frame-ancestors}
 *       is what stops an API response being framed.</li>
 * </ul>
 *
 * <h2>Why the CSP is scoped and the rest are not</h2>
 *
 * <p>{@code default-src 'none'} would break any page this app serves itself —
 * Swagger UI in dev, and the {@code /images/**} poster assets. The three simple
 * headers are safe everywhere and are applied to everything; the CSP is applied
 * to the API surface, which is the one that returns personal data.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so the headers are on the
 * response before anything can commit it — including the security chain's own
 * 401/403 writers, which flush their body themselves.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String REFERRER_POLICY = "strict-origin-when-cross-origin";
    static final String CONTENT_TYPE_OPTIONS = "nosniff";
    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()";
    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Referrer-Policy", REFERRER_POLICY);
        response.setHeader("X-Content-Type-Options", CONTENT_TYPE_OPTIONS);
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Content-Security-Policy", API_CSP);
        }
        chain.doFilter(request, response);
    }
}
