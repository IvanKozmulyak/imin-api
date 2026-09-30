package com.imin.iminapi.predictor.controller;

import com.imin.iminapi.predictor.dto.AssumptionsPatch;
import com.imin.iminapi.predictor.dto.DateCheckConfigResponse;
import com.imin.iminapi.predictor.dto.DateCheckRequest;
import com.imin.iminapi.predictor.dto.DateCheckResponse;
import com.imin.iminapi.predictor.dto.DateCheckSummaryDto;
import com.imin.iminapi.predictor.service.DateCheckService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * "Check a date" (organizer). Gated by {@code DateCheckAccess}: closed gate, foreign or missing id all 404.
 * POST and PATCH run synchronously and answer 200; validation errors are 422 FIELD_INVALID.
 */
@RestController
@RequestMapping("/api/v1/predictions/date-checks")
public class DateCheckController {

    private final DateCheckService service;

    public DateCheckController(DateCheckService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DateCheckResponse> create(@CurrentUser AuthPrincipal p, @RequestBody DateCheckRequest req) {
        return ResponseEntity.ok(service.create(p, req));
    }

    @GetMapping
    public List<DateCheckSummaryDto> list(@CurrentUser AuthPrincipal p, @RequestParam(required = false) Integer limit) {
        return service.list(p, limit);
    }

    @GetMapping("/config")
    public DateCheckConfigResponse config(@CurrentUser AuthPrincipal p) {
        return service.config(p);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DateCheckResponse> get(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return ResponseEntity.ok(service.get(p, id));
    }

    @PatchMapping("/{id}/assumptions")
    public ResponseEntity<DateCheckResponse> patchAssumptions(@CurrentUser AuthPrincipal p, @PathVariable UUID id,
                                                              @RequestBody AssumptionsPatch patch) {
        return ResponseEntity.ok(service.patchAssumptions(p, id, patch));
    }
}
