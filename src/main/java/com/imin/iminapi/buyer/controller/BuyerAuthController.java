package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.dto.BuyerAuthRequests;
import com.imin.iminapi.buyer.dto.BuyerAuthUrlResponse;
import com.imin.iminapi.buyer.dto.BuyerMeResponse;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.security.BuyerClientKind;
import com.imin.iminapi.buyer.security.BuyerOAuthNonceCookie;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.buyer.service.BuyerCredentialService;
import com.imin.iminapi.buyer.service.BuyerOAuthService;
import com.imin.iminapi.buyer.service.BuyerSessionService;
import com.imin.iminapi.oauth.AppleNativeIdentityService;
import com.imin.iminapi.oauth.GoogleOAuthService;
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
    /**
     * The buyer-side identity resolution matrix. Named for what it does rather
     * than for Google specifically: the native lanes hand it an already-verified
     * {@code OAuthUserInfo} from any provider, so it is not the "Google auth"
     * service any more.
     */
    private final BuyerOAuthService buyerIdentityResolver;
    /** ID-token verification only — no code exchange on the native lane. */
    private final GoogleOAuthService googleIdTokens;
    /**
     * Deliberately <b>not</b> {@code AppleOAuthService}: that one serves the
     * organizer web flow and hardcodes {@code emailVerified = true}, which would
     * make the buyer-side verification gate a no-op.
     */
    private final AppleNativeIdentityService appleIdentities;
    private final BuyerAccountEmailRepository emails;
    private final RateLimiter rateLimiter;

    public BuyerAuthController(BuyerSessionService sessions,
                               BuyerCredentialService credentials,
                               BuyerOAuthService buyerIdentityResolver,
                               GoogleOAuthService googleIdTokens,
                               AppleNativeIdentityService appleIdentities,
                               BuyerAccountEmailRepository emails,
                               RateLimiter rateLimiter) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.buyerIdentityResolver = buyerIdentityResolver;
        this.googleIdTokens = googleIdTokens;
        this.appleIdentities = appleIdentities;
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
        return signedInResponse(signedIn.account(), signedIn.session(), http);
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
        return signedInResponse(signedIn.account(), signedIn.session(), http);
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
        BuyerOAuthService.Authorize authorize = buyerIdentityResolver.authorizeUrl();
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
        var signedIn = buyerIdentityResolver.callback(
                req.code(), req.state(), BuyerOAuthNonceCookie.read(http), userAgent(http));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, signedIn.session().cookie().toString())
                // Single-use by construction: the nonce goes away with the state
                // it was bound to, so a replayed callback has nothing to match.
                .header(HttpHeaders.SET_COOKIE, BuyerOAuthNonceCookie.clear().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(body(signedIn.account()));
    }

    /**
     * Native Google sign-in. The app gets an ID token from the OS and posts it
     * here; there is no redirect, no {@code state} and no nonce cookie, because
     * there is no browser in the loop to bind to.
     *
     * <p>Resolution goes through the same {@link BuyerOAuthService#resolve}
     * matrix as the web callback — including the {@code email_verified} gate —
     * so the two entry points cannot diverge on who gets which account.
     */
    @PostMapping("/api/v1/buyer/auth/google/native")
    public ResponseEntity<BuyerMeResponse> googleNative(
            @Valid @RequestBody BuyerAuthRequests.NativeIdToken req,
            HttpServletRequest http) {
        // Metered first, before the config gate and before verification — the
        // same order as the wallet endpoints, so a loop cannot spin JWKS-backed
        // signature checks (or probe a disabled provider) for free. Keyed on
        // getRemoteAddr(), proxy-resolved via forward-headers-strategy, never the
        // client-controllable X-Forwarded-For. Shares one bucket with the Apple
        // lane below on purpose: see RateLimitConfig.
        rateLimiter.consume("buyer-native-signin", "ip:" + http.getRemoteAddr());

        // NOT requireGoogleEnabled(): that gate is isBuyerEnabled(), which
        // requires clientId + clientSecret + buyerRedirectUri. The native lane
        // has no redirect URI and performs no code exchange, so it needs no
        // client secret either — gating on the web flow's config would 404
        // native sign-in whenever GOOGLE_OAUTH_BUYER_REDIRECT_URI is unset.
        if (!googleIdTokens.nativeEnabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.OAUTH_PROVIDER_DISABLED,
                    "google sign-in is not configured for native clients");
        }
        var info = googleIdTokens.verifyNativeIdToken(req.idToken());
        var signedIn = buyerIdentityResolver.resolve(info, userAgent(http));
        return signedInResponse(signedIn.account(), signedIn.session(), http);
    }

    // ── Apple ──────────────────────────────────────────────────────────────

    /**
     * Native Sign in with Apple. Required by App Store Guideline 4.8 wherever
     * Google sign-in is offered, and there is no web Apple flow for buyers — the
     * organizer {@code form_post} pair is a different surface with a different
     * audience and a different verifier.
     *
     * <p>A buyer arriving on a Hide My Email relay address matches no past guest
     * order — deliberately, because identities match on subject and never on
     * email. They recover their history by adding and verifying their real
     * address under {@code /buyer/emails}, and the app should offer that
     * immediately after a first Apple sign-in.
     *
     * <p>{@code fullName} is Apple-only and first-sign-in-only: Apple returns the
     * name exactly once, so the app forwards it here on that first call or it is
     * lost. It is client-supplied and used for display only.
     */
    @PostMapping("/api/v1/buyer/auth/apple/native")
    public ResponseEntity<BuyerMeResponse> appleNative(
            @Valid @RequestBody BuyerAuthRequests.NativeIdToken req,
            HttpServletRequest http) {
        // Same bucket and same ordering as the Google lane above.
        rateLimiter.consume("buyer-native-signin", "ip:" + http.getRemoteAddr());

        if (!appleIdentities.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.OAUTH_PROVIDER_DISABLED,
                    "apple sign-in is not configured for buyers");
        }
        var info = appleIdentities.verify(req.idToken(), req.fullName());
        var signedIn = buyerIdentityResolver.resolve(info, userAgent(http));
        return signedInResponse(signedIn.account(), signedIn.session(), http);
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
        // Bearer first, cookie second — the same precedence the auth filter uses,
        // so logout always revokes the credential the caller actually presented.
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String raw = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim()
                : BuyerSessionCookie.read(request);
        sessions.revokeByRawToken(raw);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessions.clearCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .build();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void requireGoogleEnabled() {
        if (!buyerIdentityResolver.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.OAUTH_PROVIDER_DISABLED,
                    "google sign-in is not configured for buyers");
        }
    }

    /**
     * A signed-in response, in one of two mutually exclusive shapes.
     *
     * <p><b>Native clients get the token and NO cookie; browsers get the cookie
     * and no token.</b> The exclusivity is load-bearing in both directions:
     *
     * <ul>
     *   <li>Emitting {@code Set-Cookie} to a native client would break the CSRF
     *       exemption by construction. React Native's {@code fetch} runs on
     *       NSURLSession / OkHttp with the <b>platform cookie store enabled by
     *       default</b>, so the app would store {@code imin_buyer_session}
     *       (host-only, {@code Path=/api/v1/buyer}, {@code Secure} — every
     *       attribute satisfied in production) and replay it on every later
     *       request. {@link BuyerClientKind#isCookielessNative} would then
     *       return false, the guard would demand an {@code Origin} the app does
     *       not send, and logout, push-device registration and preference
     *       updates would all 403.</li>
     *   <li>Emitting the token to a browser would hand page JavaScript a
     *       portable 180-day credential — see
     *       {@link BuyerClientKind#mayReceiveRawToken}.</li>
     * </ul>
     */
    private ResponseEntity<BuyerMeResponse> signedInResponse(BuyerAccount account,
                                                             BuyerSessionService.IssuedSession session,
                                                             HttpServletRequest http) {
        BuyerMeResponse me = body(account);
        var response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE);
        if (BuyerClientKind.mayReceiveRawToken(http)) {
            return response.body(me.withSessionToken(session.rawToken()));
        }
        return response
                .header(HttpHeaders.SET_COOKIE, session.cookie().toString())
                .body(me);
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
