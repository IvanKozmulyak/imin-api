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

    @PostMapping("/signup")
    public VerificationPendingResponse signup(@Valid @RequestBody SignupRequest req) {
        return authService.signup(req);
    }

    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
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

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
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
