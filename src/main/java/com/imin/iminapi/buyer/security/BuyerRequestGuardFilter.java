package com.imin.iminapi.buyer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.buyer.BuyerProperties;
import com.imin.iminapi.security.ApiError;
import com.imin.iminapi.security.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * The CSRF defence for the buyer namespace. {@code csrf.disable()} is global in
 * {@code SecurityConfig} — irrelevant while every credential was an
 * {@code Authorization} header, load-bearing the moment a cookie became one.
 * Epic §2.6 lists four mitigations; two of them live here, and both apply to
 * every state-changing request under {@code /api/v1/buyer/**}:
 *
 * <ol>
 *   <li><b>An explicit {@code Origin} check</b> against
 *       {@code imin.buyer.allowed-origins} — an exact-string list, no
 *       wildcards. Absent {@code Origin} is a rejection, not a pass: this is
 *       the mitigation that does not depend on {@code SameSite} and does not
 *       depend on the platform-wide CORS pattern list (which contains
 *       {@code https://imin-public-*.vercel.app}, an attacker-registrable
 *       origin, next to {@code allowCredentials(true)}).</li>
 *   <li><b>A content-type check.</b> {@code application/x-www-form-urlencoded},
 *       {@code multipart/form-data} and {@code text/plain} are the three
 *       CORS-"simple" types — the shapes that reach a server with no preflight
 *       — so buyer mutations reject them outright and require JSON.</li>
 * </ol>
 *
 * <p>The other two mitigations are structural and live elsewhere:
 * {@code SameSite=Lax} on the cookie ({@link BuyerSessionCookie}) and the rule
 * that no buyer endpoint changes state on {@code GET}.
 *
 * <p><b>One deliberate relaxation of §2.6's wording.</b> The spec says every
 * buyer mutation "requires {@code Content-Type: application/json}". Taken
 * literally that breaks {@code POST /buyer/auth/logout} and
 * {@code POST /buyer/sessions/revoke-all}, which carry no body — {@code fetch}
 * sends no {@code Content-Type} for a bodyless request, and the same spec
 * insists logout must always succeed so the frontend can clean up. So: a
 * request with a body must be JSON, and a bodyless request may omit the header
 * — but any content type that IS present must still be JSON, so the
 * form-encoded and multipart shapes are rejected either way, which is the
 * property the rule exists for.
 */
@Component
public class BuyerRequestGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BuyerRequestGuardFilter.class);

    /** Standalone mapper — same reasoning as {@code SecurityConfig.AUTH_MAPPER}. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final BuyerProperties props;

    public BuyerRequestGuardFilter(BuyerProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (!BuyerSessionAuthFilter.isBuyerPath(request.getRequestURI())
                || !STATE_CHANGING.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || !props.getAllowedOrigins().contains(origin)) {
            log.debug("[buyer] rejected {} {} — origin {}", request.getMethod(), request.getRequestURI(), origin);
            write(response, 403, ErrorCode.FORBIDDEN,
                    "Request origin is not allowed for this endpoint");
            return;
        }

        if (!contentTypeAcceptable(request)) {
            log.debug("[buyer] rejected {} {} — content-type {}",
                    request.getMethod(), request.getRequestURI(), request.getContentType());
            write(response, 415, ErrorCode.INVALID_REQUEST,
                    "Buyer endpoints accept application/json only");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean contentTypeAcceptable(HttpServletRequest request) {
        String raw = request.getContentType();
        if (raw == null || raw.isBlank()) {
            // Bodyless mutation (logout, revoke-all) — see the class Javadoc.
            return request.getContentLengthLong() <= 0;
        }
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(raw));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void write(HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(), ApiError.of(code, message));
        response.getWriter().flush();
    }
}
