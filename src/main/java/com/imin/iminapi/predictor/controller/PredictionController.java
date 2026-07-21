package com.imin.iminapi.predictor.controller;

import com.imin.iminapi.predictor.dto.PredictionFeedbackRequest;
import com.imin.iminapi.predictor.dto.PredictionStatusResponse;
import com.imin.iminapi.predictor.service.PredictionRequestService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Pre-publish score endpoints (BE half of task 86cav4766) — the FROZEN contract the
 * frontend codes against:
 * <ul>
 *   <li>POST /api/v1/events/{eventId}/prediction → 202 {predictionId,status:"pending"} or
 *       200 {status:"ready",cached:true,result} on an input-hash hit. Org-scoped; manual
 *       refresh throttled (predictor-rescore) and quota-capped (kind=score, real runs only).</li>
 *   <li>GET  /api/v1/events/{eventId}/prediction → {status,result?,inputHash,generatedAt}.</li>
 *   <li>POST /api/v1/events/{eventId}/prediction/feedback {recommendationId,type} → 204.</li>
 * </ul>
 * Advisory is a hard property (spec §1): nothing here blocks publish or executes a change.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/prediction")
public class PredictionController {

    private final PredictionRequestService service;

    public PredictionController(PredictionRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> trigger(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        PredictionRequestService.Trigger t = service.trigger(p, eventId);
        return ResponseEntity.status(t.httpStatus()).body(t.body());
    }

    @GetMapping
    public PredictionStatusResponse status(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return service.status(p, eventId);
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                         @Valid @RequestBody PredictionFeedbackRequest req) {
        service.feedback(p, eventId, req);
    }
}
