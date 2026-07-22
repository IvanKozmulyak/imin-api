package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.dto.ReforecastResult;
import com.imin.iminapi.predictor.model.ReforecastTrigger;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.RateLimiter;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * HTTP-facing surface for the live re-forecast (BE of tasks 86cav479j/86cav479m). Owns org
 * scoping and the manual-refresh throttle; the projection itself lives in {@link ReforecastService}.
 *
 * <ul>
 *   <li>GET  serves the latest ledgered re-forecast ({@code none} when never forecast).</li>
 *   <li>POST recomputes synchronously (Stage 1 is cheap arithmetic), throttled on the existing
 *       {@code predictor-rescore} bucket per user, and returns the fresh result.</li>
 * </ul>
 * Advisory only (spec §1) — nothing here blocks publish or executes a change.
 */
@Service
public class ReforecastRequestService {

    private final EventRepository events;
    private final ReforecastService reforecast;
    private final RateLimiter rateLimiter;

    public ReforecastRequestService(EventRepository events, ReforecastService reforecast,
                                    RateLimiter rateLimiter) {
        this.events = events;
        this.reforecast = reforecast;
        this.rateLimiter = rateLimiter;
    }

    /** GET — the latest servable re-forecast for the caller's event. */
    public ReforecastResult status(AuthPrincipal p, UUID eventId) {
        loadOwned(p, eventId);
        return reforecast.latestServable(eventId);
    }

    /** POST — throttled manual recompute; returns the fresh result. */
    public ReforecastResult trigger(AuthPrincipal p, UUID eventId) {
        loadOwned(p, eventId);
        rateLimiter.consume("predictor-rescore", p.userId().toString());
        return reforecast.recompute(eventId, ReforecastTrigger.MANUAL);
    }

    private Event loadOwned(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }
}
