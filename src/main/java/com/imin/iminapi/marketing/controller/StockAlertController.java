package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.StockAlertSettingsRequest;
import com.imin.iminapi.marketing.dto.StockAlertsDto;
import com.imin.iminapi.marketing.stock.StockAlertService;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * An event's stock alerts; another org's event is 404, and PUT/review/dismiss are 404 while the flag is off. PUT: 403
 * below ADMIN, 400 FIELD_INVALID; review/dismiss: 409 INVALID_STATE (fields stockFacts / audience). See CLAUDE.md.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/stock-alerts")
public class StockAlertController {

    private final StockAlertService service;

    public StockAlertController(StockAlertService service) {
        this.service = service;
    }

    @GetMapping
    public StockAlertsDto get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID eventId) {
        return service.card(principal, eventId);
    }

    @PutMapping("/settings")
    public StockAlertsDto putSettings(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID eventId,
                                      @RequestBody StockAlertSettingsRequest body) {
        return service.putSettings(principal, eventId, body);
    }

    @PostMapping("/{alertId}/review")
    public CampaignDto review(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID eventId,
                              @PathVariable UUID alertId) {
        return service.review(principal, eventId, alertId);
    }

    @PostMapping("/{alertId}/dismiss")
    public ResponseEntity<Void> dismiss(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID eventId,
                                        @PathVariable UUID alertId) {
        service.dismiss(principal, eventId, alertId);
        return ResponseEntity.noContent().build();
    }
}
