package com.imin.iminapi.controller.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.ai.ConceptRegenerateRequest;
import com.imin.iminapi.dto.ai.ConceptRequest;
import com.imin.iminapi.dto.ai.ConceptResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ai.ConceptStudioService;
import com.imin.iminapi.web.IdempotencyKeySupport;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/events")
public class ConceptController {

    private static final ObjectMapper OM = new ObjectMapper();

    private final ConceptStudioService studio;
    private final IdempotencyKeySupport idempotency;
    private final RateLimiter rateLimiter;

    public ConceptController(ConceptStudioService studio,
                             IdempotencyKeySupport idempotency,
                             RateLimiter rateLimiter) {
        this.studio = studio;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/concept")
    public ConceptResponse create(@CurrentUser AuthPrincipal p,
                                  @Valid @RequestBody ConceptRequest body,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        rateLimiter.consume("ai-concept", p.userId().toString());

        if (key == null || key.isBlank()) return studio.create(p, body);

        var cached = idempotency.runOrReplay(p.orgId(), "POST /api/v1/ai/events/concept", key,
                () -> idempotency.toCached(200, studio.create(p, body)));
        try {
            return OM.readValue(cached.bodyJson(), ConceptResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialise cached concept response", e);
        }
    }

    @PostMapping("/concept/regenerate")
    public ConceptResponse regenerate(@CurrentUser AuthPrincipal p,
                                      @Valid @RequestBody ConceptRegenerateRequest body) {
        rateLimiter.consume("ai-concept", p.userId().toString());
        return studio.regenerate(p, body.conceptId(),
                body.lock() == null ? java.util.List.of() : body.lock());
    }
}
