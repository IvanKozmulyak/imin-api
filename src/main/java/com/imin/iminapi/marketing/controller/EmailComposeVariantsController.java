package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.EmailComposeVariantsRequest;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsResponse;
import com.imin.iminapi.marketing.service.EmailComposeVariantsService;
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
 * "✦ Generate email with AI" for the campaign composer's email step: whole-email variants
 * (subject + preheader + Markdown body) generated together in one call, grounded in the
 * campaign's real event/org/segment context.
 *
 * <p>Separate controller from {@code CampaignController} / {@code SubjectVariantsController} but on
 * the SAME base path — a focused surface for the single AI action, matching how
 * {@code SubjectVariantsController} is its own controller. Org comes ONLY from the auth context;
 * another org's campaign is a 404 no-leak in the service.
 *
 * <p>Rate-limited on the shared {@code ai-concept} bucket (another authenticated AI text call), so
 * the paid LLM behind it cannot be hammered.
 */
@RestController
@RequestMapping("/api/v1/marketing/campaigns")
public class EmailComposeVariantsController {

    private final EmailComposeVariantsService service;
    private final RateLimiter rateLimiter;

    public EmailComposeVariantsController(EmailComposeVariantsService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{id}/compose-variants")
    public EmailComposeVariantsResponse composeVariants(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) EmailComposeVariantsRequest body) {
        rateLimiter.consume("ai-concept", principal.userId().toString());
        Integer count = body == null ? null : body.count();
        String hint = body == null ? null : body.hint();
        return service.generate(principal, id, count, hint);
    }
}
