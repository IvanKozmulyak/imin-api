package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.dto.event.TrackRequest;
import com.imin.iminapi.service.event.FunnelTrackingService;
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

    public FunnelTrackingController(FunnelTrackingService tracking) {
        this.tracking = tracking;
    }

    @PostMapping("/{id}/track")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void track(@PathVariable UUID id, @RequestBody(required = false) TrackRequest body) {
        tracking.track(id, body);
    }
}
