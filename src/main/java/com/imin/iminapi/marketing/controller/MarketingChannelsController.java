package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.MarketingChannelsDto;
import com.imin.iminapi.marketing.service.MarketingChannelsService;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marketing channel health + guardrails. Under the shared /api/v1/marketing root, JWT +
 * org-scoped (org from AuthPrincipal, no {orgId} in the path — SPINE INVARIANT).
 *
 * <p>Read-only: this surface reports the guardrails the send path already enforces. It
 * returns no secret — no API key, no webhook signing secret, no CAPI token.
 */
@RestController
@RequestMapping("/api/v1/marketing")
public class MarketingChannelsController {

    private final MarketingChannelsService service;

    public MarketingChannelsController(MarketingChannelsService service) {
        this.service = service;
    }

    @GetMapping("/channels")
    public MarketingChannelsDto channels(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.channels(principal);
    }
}
