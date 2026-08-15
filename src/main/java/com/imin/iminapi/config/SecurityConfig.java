package com.imin.iminapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.buyer.BuyerProperties;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.BuyerRequestGuardFilter;
import com.imin.iminapi.buyer.security.BuyerSessionAuthFilter;
import com.imin.iminapi.security.ApiError;
import com.imin.iminapi.security.BearerTokenAuthFilter;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Standalone mapper for auth-error responses — avoids circular dependency with Jackson auto-config. */
    private static final ObjectMapper AUTH_MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Value("${imin.cors.allowed-origin-patterns:}")
    private String[] allowedOriginPatterns;

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(BuyerProperties buyerProps) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> patterns = Arrays.stream(allowedOriginPatterns)
                .filter(p -> p != null && !p.isBlank())
                .toList();
        config.setAllowedOriginPatterns(patterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Location"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        // Buyer endpoints get their own CORS configuration with EXACT origins
        // (imin.buyer.allowed-origins) instead of inheriting the platform-wide
        // pattern list (buyer-accounts epic §2.6). That list contains
        // `https://imin-public-*.vercel.app` next to setAllowCredentials(true),
        // and Vercel project names are first-come — an attacker who registers
        // `imin-public-anything` would otherwise sit inside a credentialed
        // allowlist for the one namespace that authenticates with a cookie.
        CorsConfiguration buyerConfig = new CorsConfiguration();
        buyerConfig.setAllowedOrigins(List.copyOf(buyerProps.getAllowedOrigins()));
        buyerConfig.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        buyerConfig.setAllowedHeaders(List.of("*"));
        buyerConfig.setAllowCredentials(true);
        buyerConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Registration ORDER is load-bearing: UrlBasedCorsConfigurationSource
        // returns the first pattern that matches, so the buyer mapping has to
        // precede the "/**" catch-all or it never applies.
        source.registerCorsConfiguration("/api/v1/buyer/**", buyerConfig);
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   BearerTokenAuthFilter bearerFilter,
                                                   BuyerRequestGuardFilter buyerGuardFilter,
                                                   BuyerSessionAuthFilter buyerSessionFilter,
                                                   CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .cors(c -> c.configurationSource(corsConfigurationSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/images/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Public AI content (event copy) generation + style-reference images.
                        .requestMatchers(HttpMethod.POST, "/api/v1/events/ai-content").permitAll()
                        .requestMatchers("/api/v1/posters/**").permitAll()
                        // V1 auth endpoints are public — login etc.
                        .requestMatchers(HttpMethod.POST,
                                         "/api/v1/auth/signup",
                                         "/api/v1/auth/login",
                                         "/api/v1/auth/logout",
                                         "/api/v1/auth/verify-email",
                                         "/api/v1/auth/resend-verification",
                                         "/api/v1/auth/forgot-password",
                                         "/api/v1/auth/reset-password").permitAll()
                        // Social sign-in (Google OIDC + Sign in with Apple) — all public.
                        // The URL/providers reads are GET; Google callback + Apple form_post
                        // + Apple server notifications are POST. Validated server-side.
                        .requestMatchers(HttpMethod.GET,
                                         "/api/v1/auth/providers",
                                         "/api/v1/auth/google/url",
                                         "/api/v1/auth/apple/url").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                         "/api/v1/auth/google/callback",
                                         "/api/v1/auth/apple/return",
                                         "/api/v1/auth/apple/notifications").permitAll()
                        // Gate-scanner login — public (no token yet); validated server-side.
                        .requestMatchers(HttpMethod.POST, "/api/v1/gate/login").permitAll()
                        // Public reference data (country codes, etc.)
                        .requestMatchers(HttpMethod.GET, "/api/v1/reference/**").permitAll()
                        // Founders-only predictor calibration view — guarded by a static bearer
                        // secret INSIDE the controller (constant-time compare; blank secret = 404
                        // dark). permitAll here so the organizer JWT chain never applies: the page
                        // aggregates cross-org ledger data no organizer principal may see.
                        .requestMatchers(HttpMethod.GET, "/api/v1/internal/predictor/calibration").permitAll()
                        // Public event endpoints (unauthenticated GET)
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                        // Public buyer checkout — unauthenticated POST (validated server-side).
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/events/*/checkout").permitAll()
                        // Native checkout — same trust model as the hosted sibling above.
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/events/*/payment-intent").permitAll()
                        // Public funnel beacon — unauthenticated POST, always 204 (no-leak).
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/events/*/track").permitAll()
                        // Public "notify me when tickets release" subscription — unauthenticated POST.
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/events/*/notify").permitAll()
                        // Public price-quote preview (promo validation, totals) — unauthenticated POST.
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/events/*/quote").permitAll()
                        // Lost-email recovery — unauthenticated POST, always 204, rate-limited.
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/orders/recover").permitAll()
                        // Post-purchase SMS marketing opt-in — unauthenticated POST (§4).
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/orders/*/sms-consent").permitAll()
                        // Buyer-initiated refund-request surface (link issuance + form submit).
                        .requestMatchers(HttpMethod.POST,
                                         "/api/v1/public/refund-requests",
                                         "/api/v1/public/refund-requests/**").permitAll()
                        // Stripe webhooks — unauthenticated; signature-verified inside the handler.
                        // Two endpoints: /webhook/v1 (V1 payments) and /webhook/v2 (V2 thin events).
                        .requestMatchers(HttpMethod.POST, "/api/v1/stripe/webhook/**").permitAll()
                        // Resend marketing webhook — unauthenticated; Svix-signature verified in the handler.
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/webhooks/resend").permitAll()
                        // Inbound SMS webhook (STOP replies + delivery receipts) — unauthenticated;
                        // HMAC-signature verified in the handler (blank secret ⇒ every call 401s).
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/webhooks/sms").permitAll()
                        // Owned marketing opt-out (RFC 8058 one-click POST + confirm page) — unauthenticated,
                        // signed-token-verified inside the handler.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/unsubscribe/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/unsubscribe/**").permitAll()
                        // ── Buyer accounts (imin-public / app.imin.wtf) ──────────────
                        // A separate namespace from /api/v1/public/**, which is blanket
                        // permitAll on GET (:102 above): keeping the two disjoint makes
                        // "buyer data is never public" an invariant instead of a list of
                        // exceptions. Credential endpoints are unauthenticated by nature
                        // and must be permitted BEFORE the /api/v1/** catch-all or they
                        // 401 before reaching a controller. Signup/login/verify/reset and
                        // the Google pair land in R1.2; permitting them now keeps the
                        // security rule in one commit.
                        //
                        // logout is permitted deliberately: it must clear the cookie and
                        // answer 204 even when the session has already expired, or the
                        // frontend can never clean up. It is idempotent, carries no data,
                        // and is still covered by the Origin check in BuyerRequestGuardFilter.
                        .requestMatchers(HttpMethod.POST,
                                         "/api/v1/buyer/auth/signup",
                                         "/api/v1/buyer/auth/login",
                                         "/api/v1/buyer/auth/logout",
                                         "/api/v1/buyer/auth/verify-email",
                                         "/api/v1/buyer/auth/resend-verification",
                                         "/api/v1/buyer/auth/forgot-password",
                                         "/api/v1/buyer/auth/reset-password",
                                         "/api/v1/buyer/auth/google/callback",
                                         // Native sign-in: the app posts an OS-issued ID
                                         // token instead of driving a browser redirect.
                                         // Unauthenticated by nature, like the callback.
                                         // Apple has no web counterpart on the buyer
                                         // surface — App Store Guideline 4.8 needs it
                                         // wherever Google sign-in is offered.
                                         "/api/v1/buyer/auth/google/native",
                                         "/api/v1/buyer/auth/apple/native").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                         "/api/v1/buyer/auth/google/url").permitAll()
                        // hasRole("BUYER"), not .authenticated(): an organizer bearer
                        // token authenticates with ROLE_OWNER/ADMIN/MEMBER and must not
                        // be able to read a buyer's account or order history.
                        .requestMatchers("/api/v1/buyer/**").hasRole(BuyerPrincipal.ROLE)
                        // Everything else under /api/v1 requires a session
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                // Order is explicit and relative to the bearer filter rather than to
                // UsernamePasswordAuthenticationFilter, so it cannot depend on
                // registration ties: guard → bearer → buyer session. The guard rejects
                // cross-site mutations before any credential is resolved, and the buyer
                // filter runs last so it no-ops when a bearer token already authenticated.
                .addFilterBefore(buyerGuardFilter, BearerTokenAuthFilter.class)
                .addFilterAfter(buyerSessionFilter, BearerTokenAuthFilter.class)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, resp, ex) -> {
                            ErrorCode errorCode = (ErrorCode) req.getAttribute("imin.authErrorCode");
                            if (errorCode == null) errorCode = ErrorCode.AUTH_MISSING;
                            String message = errorCode == ErrorCode.AUTH_TOKEN_EXPIRED
                                    ? "Authentication token has expired" : "Authentication required";
                            resp.setStatus(401);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            resp.setCharacterEncoding("UTF-8");
                            AUTH_MAPPER.writeValue(resp.getWriter(), ApiError.of(errorCode, message));
                            resp.getWriter().flush();
                        })
                        .accessDeniedHandler((req, resp, ex) -> {
                            resp.setStatus(403);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            resp.setCharacterEncoding("UTF-8");
                            AUTH_MAPPER.writeValue(resp.getWriter(),
                                    ApiError.of(ErrorCode.FORBIDDEN, "Access denied"));
                            resp.getWriter().flush();
                        })
                );
        return http.build();
    }
}
