package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.dto.event.TrackRequest;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.event.FunnelTrackingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public, unauthenticated funnel beacon. Always 204 — including for unknown or
 * non-public events (no-leak). The body is best-effort; bad input is a no-op.
 */
@RestController
@RequestMapping("/api/v1/public/events")
public class FunnelTrackingController {

    private final FunnelTrackingService tracking;
    private final RateLimiter rateLimiter;

    public FunnelTrackingController(FunnelTrackingService tracking, RateLimiter rateLimiter) {
        this.tracking = tracking;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Metered per client IP: the endpoint is unauthenticated and every accepted
     * call is a row in {@code event_funnel_events}, so an unthrottled loop is a
     * write amplifier that also poisons the organizer's funnel numbers.
     *
     * <p>A full bucket <b>drops the beacon and still answers 204</b> rather than
     * 429. The always-204 contract is load-bearing — it is what stops the
     * endpoint leaking whether an event exists — and analytics is the one thing
     * on this API where losing a write is the correct failure.
     */
    @PostMapping("/{id}/track")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void track(@PathVariable UUID id, @RequestBody(required = false) TrackRequest body,
                      HttpServletRequest http) {
        try {
            rateLimiter.consume("public-track", "ip:" + http.getRemoteAddr());
        } catch (ApiException e) {
            return;
        }
        tracking.track(id, body);
    }
}
