package com.imin.iminapi.predictor.controller;

import com.imin.iminapi.predictor.dto.RadarMuteRequest;
import com.imin.iminapi.predictor.dto.RadarTimelineResponse;
import com.imin.iminapi.predictor.service.RadarTimelineService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * An event's Radar re-checks and its alert mute (organizer auth, any org member):
 * <ul>
 *   <li>GET /api/v1/events/{eventId}/prediction/radar → 200 {@link RadarTimelineResponse}: {@code radarOn} (the
 *       global Radar switch), {@code muted}, and at most 20 runs newest first, each frozen at run time.</li>
 *   <li>PUT /api/v1/events/{eventId}/prediction/radar/mute {muted} → 200 the same response. 400
 *       {@code FIELD_INVALID fields.muted=required}. The mute stops Radar alerts only, never band alerts or runs.</li>
 * </ul>
 * Both: 404 when the date-check gate is closed for the org, or for an unknown, deleted or other-org event; the gate
 * is checked first, then the event, then the body.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/prediction/radar")
public class RadarController {

    private final RadarTimelineService service;

    public RadarController(RadarTimelineService service) {
        this.service = service;
    }

    @GetMapping
    public RadarTimelineResponse timeline(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return service.timeline(p, eventId);
    }

    // Body optional so a missing one is the service's 400, after the gate and event checks.
    @PutMapping("/mute")
    public RadarTimelineResponse mute(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                      @RequestBody(required = false) RadarMuteRequest req) {
        return service.mute(p, eventId, req);
    }
}
