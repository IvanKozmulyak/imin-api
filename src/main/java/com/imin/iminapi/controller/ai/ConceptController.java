package com.imin.iminapi.controller.ai;

import com.imin.iminapi.dto.ai.ConceptRegenerateRequest;
import com.imin.iminapi.dto.ai.ConceptRequest;
import com.imin.iminapi.dto.ai.ConceptResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ai.ConceptStudioService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/events")
public class ConceptController {

    private final ConceptStudioService studio;
    private final RateLimiter rateLimiter;

    public ConceptController(ConceptStudioService studio, RateLimiter rateLimiter) {
        this.studio = studio;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/concept")
    public ConceptResponse create(@CurrentUser AuthPrincipal p,
                                  @Valid @RequestBody ConceptRequest body) {
        rateLimiter.consume("ai-concept", p.userId().toString());
        return studio.create(p, body);
    }

    @PostMapping("/concept/regenerate")
    public ConceptResponse regenerate(@CurrentUser AuthPrincipal p,
                                      @Valid @RequestBody ConceptRegenerateRequest body) {
        rateLimiter.consume("ai-concept", p.userId().toString());
        return studio.regenerate(p, body.conceptId(),
                body.lock() == null ? java.util.List.of() : body.lock());
    }
}
