package com.imin.iminapi.controller.auth;

import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.dto.auth.AuthUrlResponse;
import com.imin.iminapi.dto.auth.GoogleCallbackRequest;
import com.imin.iminapi.dto.auth.ProvidersResponse;
import com.imin.iminapi.oauth.AppleOAuthService;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.oauth.OAuthAccountService;
import com.imin.iminapi.oauth.OAuthProperties;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Social sign-in endpoints (Google OIDC + Sign in with Apple), all public
 * (permit-listed in {@code SecurityConfig}). They live alongside
 * {@link AuthController} under {@code /api/v1/auth} and end in a normal USER
 * {@link AuthResponse} — the same session a password login yields.
 *
 * <p>Both providers are config-gated: when a provider's secrets are unset the
 * URL endpoints return a clean 404 and {@code /providers} reports it disabled,
 * so the frontend hides the button and no user hits a dead end.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthProperties props;
    private final GoogleOAuthService google;
    private final AppleOAuthService apple;
    private final OAuthAccountService accounts;

    public OAuthController(OAuthProperties props,
                           GoogleOAuthService google,
                           AppleOAuthService apple,
                           OAuthAccountService accounts) {
        this.props = props;
        this.google = google;
        this.apple = apple;
        this.accounts = accounts;
    }

    @GetMapping("/providers")
    public ProvidersResponse providers() {
        return new ProvidersResponse(props.getGoogle().isEnabled(), props.getApple().isEnabled());
    }

    // ----- Google (SPA-mediated: browser returns to the FE, which POSTs the code) -----

    @GetMapping("/google/url")
    public AuthUrlResponse googleUrl() {
        requireEnabled(props.getGoogle().isEnabled(), "google");
        return new AuthUrlResponse(google.buildAuthorizeUrl());
    }

    @PostMapping("/google/callback")
    public AuthResponse googleCallback(@Valid @RequestBody GoogleCallbackRequest req) {
        requireEnabled(props.getGoogle().isEnabled(), "google");
        OAuthUserInfo info = google.exchange(req.code(), req.state());
        return accounts.resolve(info);
    }

    // ----- Apple (backend-mediated: Apple form_posts here, we 302 back to the FE) -----

    @GetMapping("/apple/url")
    public AuthUrlResponse appleUrl() {
        requireEnabled(props.getApple().isEnabled(), "apple");
        return new AuthUrlResponse(apple.buildAuthorizeUrl());
    }

    /**
     * Apple posts the auth code here (response_mode=form_post). On success we
     * 302-redirect to the FE with the session token in the URL <b>fragment</b>
     * (never a query param — fragments aren't sent to servers or written to
     * access logs). On any failure we bounce to the login page with an error flag.
     */
    @PostMapping(path = "/apple/return", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> appleReturn(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(name = "user", required = false) String user,
            @RequestParam(name = "error", required = false) String error) {
        String frontend = props.getFrontendBaseUrl();
        if (!props.getApple().isEnabled() || error != null || code == null || code.isBlank()) {
            if (error != null) log.info("Apple return with error param: {}", error);
            return redirect(frontend + "/auth/login?oauth_error=1");
        }
        try {
            OAuthUserInfo info = apple.exchange(code, state, user);
            AuthResponse resp = accounts.resolve(info);
            return redirect(frontend + "/auth/callback/apple#token=" + resp.token());
        } catch (RuntimeException e) {
            log.warn("Apple sign-in failed: {}", e.getMessage());
            return redirect(frontend + "/auth/login?oauth_error=1");
        }
    }

    /** Apple server-to-server notifications (consent revoked / account deleted). Always 200. */
    @PostMapping(path = "/apple/notifications", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public void appleNotifications(@RequestParam(name = "payload", required = false) String payload) {
        apple.handleServerNotification(payload);
    }

    // ----- helpers -----

    private static void requireEnabled(boolean enabled, String provider) {
        if (!enabled) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.OAUTH_PROVIDER_DISABLED,
                    provider + " sign-in is not configured");
        }
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
