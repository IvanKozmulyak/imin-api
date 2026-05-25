package com.imin.iminapi.controller.gate;

import com.imin.iminapi.dto.gate.GateLoginRequest;
import com.imin.iminapi.dto.gate.GateLoginResponse;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.gate.GateAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public gate-device login endpoint. Rate-limited per (orgSlug) on the
 * existing "login" bucket so a misconfigured gate can't DoS itself — the
 * same bucket the user-side {@code POST /api/v1/auth/login} uses, so the
 * keyspace is shared (slug vs email-address) but the bucket is generous
 * enough (5/15min) that real-world collisions are vanishingly unlikely.
 */
@RestController
public class GateLoginController {

    private final GateAuthService service;
    private final RateLimiter rateLimiter;

    public GateLoginController(GateAuthService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/v1/gate/login")
    public GateLoginResponse login(@Valid @RequestBody GateLoginRequest req) {
        rateLimiter.consume("login", "gate:" + req.orgSlug().toLowerCase());
        return service.login(req);
    }
}
