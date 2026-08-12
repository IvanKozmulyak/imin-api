package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.buyer.service.BuyerSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated buyer credential endpoints. R1.1 ships only logout; signup,
 * verify-email, resend, login, forgot/reset-password and the two Google
 * endpoints land in R1.2 on the same base path and the same permit block
 * (epic §3.2).
 */
@RestController
public class BuyerAuthController {

    private final BuyerSessionService sessions;

    public BuyerAuthController(BuyerSessionService sessions) {
        this.sessions = sessions;
    }

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
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }
}
