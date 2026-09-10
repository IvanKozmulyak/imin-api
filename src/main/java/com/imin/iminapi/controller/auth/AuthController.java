package com.imin.iminapi.controller.auth;

import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.dto.auth.ForgotPasswordRequest;
import com.imin.iminapi.dto.auth.LoginRequest;
import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.auth.ResendVerificationRequest;
import com.imin.iminapi.dto.auth.ResetPasswordRequest;
import com.imin.iminapi.dto.auth.SignupRequest;
import com.imin.iminapi.dto.auth.VerificationPendingResponse;
import com.imin.iminapi.dto.auth.VerifyEmailRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter;

    public AuthController(AuthService authService, RateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Keyed per client IP, not per email: an email key would let an attacker burn
     * a stranger's bucket and stop them registering at all. Same reasoning, and
     * the same numbers, as {@code buyer-signup}.
     */
    @PostMapping("/signup")
    public VerificationPendingResponse signup(@Valid @RequestBody SignupRequest req,
                                              HttpServletRequest http) {
        rateLimiter.consume("signup", "ip:" + http.getRemoteAddr());
        return authService.signup(req);
    }

    /**
     * Keyed per address, because the address is what is under attack: a correct
     * guess here returns a live session for that organization. This bucket is the
     * fast lane only — the control that has to hold is the DB-counted lockout in
     * {@code EmailVerificationService}, since {@code RateLimitConfig} is
     * {@code @Profile("!test")} and a limit the suite cannot assert on regresses.
     */
    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        rateLimiter.consume("verify-email", req.email().toLowerCase());
        return authService.verifyEmail(req);
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.OK)
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest req) {
        rateLimiter.consume("verification-resend", req.email().toLowerCase());
        authService.resendVerification(req);
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        rateLimiter.consume("password-reset", req.email().toLowerCase());
        authService.forgotPassword(req);
    }

    /**
     * Keyed per client IP — the only key that exists before the token is
     * resolved. The token itself is long and opaque, so this is a floor under
     * the cost of hammering the endpoint rather than a guessing control.
     */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest req,
                              HttpServletRequest http) {
        rateLimiter.consume("reset-password-token", "ip:" + http.getRemoteAddr());
        authService.resetPassword(req);
    }

    @PostMapping("/change-password")
    public AuthResponse changePassword(@CurrentUser AuthPrincipal principal,
                                       @Valid @RequestBody com.imin.iminapi.dto.auth.ChangePasswordRequest req) {
        rateLimiter.consume("login", principal.userId().toString());
        return authService.changePassword(principal, req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        rateLimiter.consume("login", req.email().toLowerCase());
        return authService.login(req);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CurrentUser AuthPrincipal principal) {
        if (principal != null) authService.logout(principal);
    }

    @GetMapping("/me")
    public MeResponse me(@CurrentUser AuthPrincipal principal) {
        return authService.me(principal);
    }
}
