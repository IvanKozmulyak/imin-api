package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.MarketingHubMetricsDto;
import com.imin.iminapi.marketing.service.MarketingHubService;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marketing hub tiles. Under the shared /api/v1/marketing root, JWT + org-scoped
 * (org from AuthPrincipal, no {orgId} in the path — SPINE INVARIANT).
 */
@RestController
@RequestMapping("/api/v1/marketing")
public class MarketingHubController {

    private final MarketingHubService service;

    public MarketingHubController(MarketingHubService service) {
        this.service = service;
    }

    @GetMapping("/hub-metrics")
    public MarketingHubMetricsDto hubMetrics(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.metrics(principal);
    }
}
