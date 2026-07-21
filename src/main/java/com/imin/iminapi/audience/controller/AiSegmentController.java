package com.imin.iminapi.audience.controller;

import com.imin.iminapi.audience.dto.AiSegmentDraftRequest;
import com.imin.iminapi.audience.dto.AiSegmentDraftResponse;
import com.imin.iminapi.audience.service.AiSegmentService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI segment drafting: turns a natural-language audience description into validated segment
 * filters + a size preview, WITHOUT persisting anything. The organizer reviews the draft and
 * confirms via the normal {@code POST /audience/segments} create flow.
 *
 * <p>Separate controller from {@code AudienceController} but on the SAME base path — a focused
 * surface for the single AI action, mirroring how {@code SubjectVariantsController} /
 * {@code ConceptController} split their AI endpoints out. Org comes ONLY from the auth context.
 *
 * <p>Rate-limited on the shared {@code ai-concept} burst bucket (one paid LLM call per request);
 * there is no separate daily text quota for this path.
 */
@RestController
@RequestMapping("/api/v1/audience/segments")
public class AiSegmentController {

    private final AiSegmentService service;
    private final RateLimiter rateLimiter;

    public AiSegmentController(AiSegmentService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/ai-draft")
    public AiSegmentDraftResponse draft(@CurrentUser AuthPrincipal p,
                                        @Valid @RequestBody AiSegmentDraftRequest body) {
        rateLimiter.consume("ai-concept", p.userId().toString());
        return service.draft(p, body.prompt());
    }
}
