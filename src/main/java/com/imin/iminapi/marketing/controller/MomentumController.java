package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.service.MomentumService;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Momentum Engine endpoints (spec §6.4). Under the shared /api/v1/marketing root,
 * JWT + org-scoped (org from AuthPrincipal, no {orgId} in the path). Approve creates
 * a normal draft campaign (origin='momentum') and returns it — never sends.
 */
@RestController
@RequestMapping("/api/v1/marketing/suggestions")
public class MomentumController {

    private final MomentumService service;

    public MomentumController(MomentumService service) {
        this.service = service;
    }

    @GetMapping
    public List<MomentumSuggestionDto> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "status", required = false) String status) {
        return service.list(principal, status);
    }

    @PostMapping("/{id}/approve")
    public CampaignDto approve(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id) {
        return service.approve(principal, id);
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id) {
        service.dismiss(principal, id);
        return ResponseEntity.noContent().build();
    }
}
