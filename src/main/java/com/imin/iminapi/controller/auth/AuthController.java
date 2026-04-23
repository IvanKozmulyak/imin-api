package com.imin.iminapi.controller.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.dto.auth.LoginRequest;
import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.auth.SignupRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.auth.AuthService;
import com.imin.iminapi.web.IdempotencyKeySupport;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final IdempotencyKeySupport idempotency;
    private final RateLimiter rateLimiter;

    @Autowired @Lazy
    private ObjectMapper om;

    public AuthController(AuthService authService,
                          IdempotencyKeySupport idempotency,
                          RateLimiter rateLimiter) {
        this.authService = authService;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody SignupRequest req,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        // Idempotency on signup keys per-email since there's no org yet.
        if (key == null || key.isBlank()) return authService.signup(req);
        // Use a synthetic orgId of all-zeros for pre-org idempotency scope.
        java.util.UUID scope = new java.util.UUID(0L, 0L);
        var cached = idempotency.runOrReplay(scope, "POST /api/v1/auth/signup", key, () -> {
            AuthResponse r = authService.signup(req);
            return idempotency.toCached(200, r);
        });
        try {
            return om.readValue(cached.bodyJson(), AuthResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialise cached signup response", e);
        }
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
