package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.FollowOptInRequest;
import com.imin.iminapi.audienceplan.service.FollowOptInService;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated "Keep me posted" form on imin.wtf, called from the visitor's browser (CORS for this path only).
 * Answers 200 {@code {ok: true}} for every accepted sign-up, stored or not; 404, 400 with fields, or 429 otherwise.
 */
@RestController
public class PublicFollowController {

    /** Same body whether the address was stored or not, so the answer never reveals an address's state. */
    public record FollowOptInResponse(boolean ok) {}

    private final FollowOptInService service;
    private final RateLimiter rateLimiter;

    public PublicFollowController(FollowOptInService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    /** Charged before any lookup or validation, per client IP (getRemoteAddr, resolved from the proxy). */
    @PostMapping("/api/v1/public/orgs/{slug}/follow")
    public FollowOptInResponse follow(@PathVariable String slug,
                                      @RequestBody(required = false) FollowOptInRequest body,
                                      HttpServletRequest http) {
        rateLimiter.consume("follow-optin", "ip:" + http.getRemoteAddr());
        service.follow(slug, body);
        return new FollowOptInResponse(true);
    }
}
