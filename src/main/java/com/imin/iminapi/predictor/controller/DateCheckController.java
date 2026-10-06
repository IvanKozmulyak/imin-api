package com.imin.iminapi.predictor.controller;

import com.imin.iminapi.predictor.dto.AssumptionsPatch;
import com.imin.iminapi.predictor.dto.DateCheckConfigResponse;
import com.imin.iminapi.predictor.dto.DateCheckRequest;
import com.imin.iminapi.predictor.dto.DateCheckResponse;
import com.imin.iminapi.predictor.dto.DateCheckSummaryDto;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.service.DateCheckService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
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
 * POST answers 200 with the finished check, or 202 with the same body while its web research runs
 * ({@code status} and {@code researchStatus} "running"; poll GET). PATCH answers 200. Validation errors are 422
 * FIELD_INVALID.
 */
@RestController
@RequestMapping("/api/v1/predictions/date-checks")
public class DateCheckController {

    private final DateCheckService service;

    public DateCheckController(DateCheckService service) {
        this.service = service;
    }

    @PostMapping
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Checked; no web research running"),
            @ApiResponse(responseCode = "202", description = "Calendar result stored; web research running")})
    public ResponseEntity<DateCheckResponse> create(@CurrentUser AuthPrincipal p, @RequestBody DateCheckRequest req) {
        DateCheckResponse r = service.create(p, req);
        return DateCheck.RESEARCH_RUNNING.equals(r.researchStatus())
                ? ResponseEntity.status(HttpStatus.ACCEPTED).body(r)
                : ResponseEntity.ok(r);
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
