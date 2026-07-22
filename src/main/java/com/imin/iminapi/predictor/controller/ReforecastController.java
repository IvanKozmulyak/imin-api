package com.imin.iminapi.predictor.controller;

import com.imin.iminapi.predictor.dto.ReforecastResult;
import com.imin.iminapi.predictor.service.ReforecastRequestService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Live re-forecast endpoints (BE of tasks 86cav479j/86cav479m) — the FROZEN contract an FE agent
 * codes the pacing view against:
 * <ul>
 *   <li>GET  /api/v1/events/{eventId}/reforecast → {@link ReforecastResult}
 *       (status none|insufficient_data|ready, stage, band, projectedFinalRange?, sellOutEta?,
 *       pacing?, narration?, alert?, ledger, velocity, revenueRangeMinor?, generatedAt).</li>
 *   <li>POST /api/v1/events/{eventId}/reforecast → recompute now (throttled) → fresh result.</li>
 * </ul>
 * Org-scoped; advisory only (spec §1).
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/reforecast")
public class ReforecastController {

    private final ReforecastRequestService service;

    public ReforecastController(ReforecastRequestService service) {
        this.service = service;
    }

    @GetMapping
    public ReforecastResult status(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return service.status(p, eventId);
    }

    @PostMapping
    public ReforecastResult trigger(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return service.trigger(p, eventId);
    }
}
