package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.SubjectVariantsRequest;
import com.imin.iminapi.marketing.dto.SubjectVariantsResponse;
import com.imin.iminapi.marketing.service.SubjectVariantsService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.RateLimiter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * "✨ AI variants" for the campaign composer's email content step (spec §6): three distinct
 * subject-line alternates for one campaign, generated on demand from its real event data.
 *
 * <p>Separate controller from {@link CampaignController} but on the SAME base path — a focused
 * surface for the single AI action, matching how {@code ConceptController} is its own controller.
 * Org comes ONLY from the auth context; another org's campaign is a 404 no-leak in the service.
 *
 * <p>Rate-limited on the shared {@code ai-concept} bucket (this is another authenticated AI
 * text call), so the paid LLM behind it cannot be hammered.
 */
@RestController
@RequestMapping("/api/v1/marketing/campaigns")
public class SubjectVariantsController {

    private final SubjectVariantsService service;
    private final RateLimiter rateLimiter;

    public SubjectVariantsController(SubjectVariantsService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{id}/subject-variants")
    public SubjectVariantsResponse subjectVariants(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) SubjectVariantsRequest body) {
        rateLimiter.consume("ai-concept", principal.userId().toString());
        return service.generate(principal, id, body == null ? null : body.currentSubject());
    }
}
