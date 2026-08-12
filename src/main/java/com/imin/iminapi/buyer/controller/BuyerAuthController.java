package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.dto.BuyerAuthRequests;
import com.imin.iminapi.buyer.dto.BuyerAuthUrlResponse;
import com.imin.iminapi.buyer.dto.BuyerMeResponse;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.security.BuyerOAuthNonceCookie;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.buyer.service.BuyerCredentialService;
import com.imin.iminapi.buyer.service.BuyerOAuthService;
import com.imin.iminapi.buyer.service.BuyerSessionService;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated buyer credential endpoints, all under
 * {@code /api/v1/buyer/auth} and all permit-listed in {@code SecurityConfig}
 * (epic §3.2) — they have to be, or they 401 at the {@code /api/v1/**}
 * catch-all before reaching a controller.
 *
 * <h2>What "always 204" means here</h2>
 *
 * <p>{@code signup}, {@code resend-verification} and {@code forgot-password}
 * answer <b>204 with an empty body on every branch</b>: address unknown, address
 * claimed but unverified, address verified on someone's account, mail provider
 * down. Identical status, identical body, identical shape under rate limiting.
 * That is the whole anti-enumeration contract (§2.2) — {@code AuthService
 * .forgotPassword:211-224} is the pattern being followed and
 * {@code signup:100-103}'s {@code 409 DUPLICATE} is the anti-pattern being
 * avoided. The branch behaviour lives in {@link BuyerCredentialService}; nothing
 * about it is observable from out here, which is the point.
 *
 * <p>{@code login} answers one generic 401 for every credential failure, and
 * {@code 403 EMAIL_NOT_VERIFIED} only after a correct password.
 *
 * <p>Every response carries {@code Cache-Control: private, no-store}.
 */
@RestController
public class BuyerAuthController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerSessionService sessions;
    private final BuyerCredentialService credentials;
    private final BuyerOAuthService googleAuth;
    private final BuyerAccountEmailRepository emails;
    private final RateLimiter rateLimiter;

    public BuyerAuthController(BuyerSessionService sessions,
                               BuyerCredentialService credentials,
                               BuyerOAuthService googleAuth,
                               BuyerAccountEmailRepository emails,
                               RateLimiter rateLimiter) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.googleAuth = googleAuth;
        this.emails = emails;
        this.rateLimiter = rateLimiter;
    }

    // ── Email + password ───────────────────────────────────────────────────

    /**
     * Keyed per client IP rather than per address: keying signup on the email
     * would let an attacker lock a stranger out of registering by burning their
     * bucket, and the abuse being throttled here (mass account creation) is
     * per-origin anyway. {@code forward-headers-strategy=framework} makes
     * {@code getRemoteAddr()} the real client IP in production.
     */
    @PostMapping("/api/v1/buyer/auth/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody BuyerAuthRequests.Signup req,
                                       HttpServletRequest http) {
        rateLimiter.consume("buyer-signup", "ip:" + http.getRemoteAddr());
        credentials.signup(req.email(), req.password(), req.locale());
        return noContent();
    }

    /**
     * Redeems the six-digit code and signs the buyer in — 200 with the account
     * plus {@code Set-Cookie}. Not rate-limited by a bucket: the DB-counted
     * per-address lockout in {@code BuyerEmailVerificationService} is the
     * control here, precisely because the test suite can assert on it.
     */
    @PostMapping("/api/v1/buyer/auth/verify-email")
    public ResponseEntity<BuyerMeResponse> verifyEmail(@Valid @RequestBody BuyerAuthRequests.VerifyEmail req,
                                                       HttpServletRequest http) {
        var signedIn = credentials.verifyEmail(req.email(), req.code(), userAgent(http));
        return signedInResponse(signedIn.account(), signedIn.session());
    }

    @PostMapping("/api/v1/buyer/auth/resend-verification")
    public ResponseEntity<Void> resendVerification(
            @Valid @RequestBody BuyerAuthRequests.ResendVerification req) {
        rateLimiter.consume("buyer-verification-resend", EmailNormalizer.normalize(req.email()));
        credentials.resendVerification(req.email());
        return noContent();
    }

    @PostMapping("/api/v1/buyer/auth/login")
    public ResponseEntity<BuyerMeResponse> login(@Valid @RequestBody BuyerAuthRequests.Login req,
                                                 HttpServletRequest http) {
        rateLimiter.consume("buyer-login", EmailNormalizer.normalize(req.email()));
        var signedIn = credentials.login(req.email(), req.password(), userAgent(http));
        return signedInResponse(signedIn.account(), signedIn.session());
    }

    @PostMapping("/api/v1/buyer/auth/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody BuyerAuthRequests.ForgotPassword req) {
        rateLimiter.consume("buyer-password-reset", EmailNormalizer.normalize(req.email()));
        credentials.forgotPassword(req.email());
        return noContent();
    }

    /**
     * Consumes the reset token and revokes every session. Answers 204 without a
     * cookie: the buyer signs in again with the new password, which is the
     * behaviour the notification email describes.
     */
    @PostMapping("/api/v1/buyer/auth/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody BuyerAuthRequests.ResetPassword req) {
        credentials.resetPassword(req.token(), req.password());
        return noContent();
    }

    // ── Google ─────────────────────────────────────────────────────────────

    /**
     * Hands out the authorize URL and sets {@code imin_oauth_nonce}, whose
     * SHA-256 is sealed inside the state. The callback will not complete without
     * it, which is what stops an attacker handing a victim a pre-authorized
     * callback URL and landing them in the attacker's account (§2.4).
     */
    @GetMapping("/api/v1/buyer/auth/google/url")
    public ResponseEntity<BuyerAuthUrlResponse> googleUrl() {
        requireGoogleEnabled();
        BuyerOAuthService.Authorize authorize = googleAuth.authorizeUrl();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authorize.nonceCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(new BuyerAuthUrlResponse(authorize.url()));
    }

    /**
     * The imin-public callback page POSTs {@code {code, state}} here. Rejects a
     * state minted for the organizer audience, and a state whose browser binding
     * does not match this request's nonce cookie. The nonce cookie is cleared
     * either way — it is single-use by construction.
     */
    @PostMapping("/api/v1/buyer/auth/google/callback")
    public ResponseEntity<BuyerMeResponse> googleCallback(
            @Valid @RequestBody BuyerAuthRequests.GoogleCallback req,
            HttpServletRequest http) {
        requireGoogleEnabled();
        var signedIn = googleAuth.callback(
                req.code(), req.state(), BuyerOAuthNonceCookie.read(http), userAgent(http));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, signedIn.session().cookie().toString())
                // Single-use by construction: the nonce goes away with the state
                // it was bound to, so a replayed callback has nothing to match.
                .header(HttpHeaders.SET_COOKIE, BuyerOAuthNonceCookie.clear().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(body(signedIn.account()));
    }

    // ── Logout ─────────────────────────────────────────────────────────────

    /**
     * Revokes the acting session and clears the cookie. <b>Always 204</b>, even
     * with no cookie, an unknown cookie or an already-expired session: it is
     * permit-listed precisely so the frontend can always clean up, and it
     * leaks nothing because it carries no body and reveals no state. The
     * {@code Origin} check in {@code BuyerRequestGuardFilter} still applies, so
     * a foreign site cannot log a buyer out as a nuisance.
     */
    @PostMapping("/api/v1/buyer/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.revokeByRawToken(BuyerSessionCookie.read(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessions.clearCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .build();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void requireGoogleEnabled() {
        if (!googleAuth.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.OAUTH_PROVIDER_DISABLED,
                    "google sign-in is not configured for buyers");
        }
    }

    private ResponseEntity<BuyerMeResponse> signedInResponse(BuyerAccount account,
                                                             BuyerSessionService.IssuedSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, session.cookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(body(account));
    }

    /** Same projection as {@code GET /buyer/me}, so a sign-in and a reload agree. */
    private BuyerMeResponse body(BuyerAccount account) {
        return BuyerMeResponse.of(
                account, emails.findByBuyerAccountIdOrderByCreatedAtAsc(account.getId()));
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }

    private static String userAgent(HttpServletRequest http) {
        return http.getHeader(HttpHeaders.USER_AGENT);
    }
}
