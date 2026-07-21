package com.imin.iminapi.controller.ai;

import com.imin.iminapi.dto.ai.ConceptRegenerateRequest;
import com.imin.iminapi.dto.ai.ConceptRequest;
import com.imin.iminapi.dto.ai.ConceptResponse;
import com.imin.iminapi.dto.ai.ConceptSetRequest;
import com.imin.iminapi.dto.ai.ConceptSetResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ai.AiQuotaService;
import com.imin.iminapi.service.ai.ConceptSetService;
import com.imin.iminapi.service.ai.ConceptStudioService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/events")
public class ConceptController {

    private final ConceptStudioService studio;
    private final ConceptSetService conceptSet;
    private final RateLimiter rateLimiter;
    private final AiQuotaService aiQuota;

    public ConceptController(ConceptStudioService studio, ConceptSetService conceptSet,
                             RateLimiter rateLimiter, AiQuotaService aiQuota) {
        this.studio = studio;
        this.conceptSet = conceptSet;
        this.rateLimiter = rateLimiter;
        this.aiQuota = aiQuota;
    }

    @PostMapping("/concept")
    public ConceptResponse create(@CurrentUser AuthPrincipal p,
                                  @Valid @RequestBody ConceptRequest body) {
        rateLimiter.consume("ai-concept", p.userId().toString());
        aiQuota.checkAndRecordImage(p);
        return studio.create(p, body);
    }

    @PostMapping("/concepts")
    public ConceptSetResponse createSet(@CurrentUser AuthPrincipal p,
                                        @Valid @RequestBody ConceptSetRequest body) {
        // Text-only concept set — burst-limited only; no daily image quota (poster pipeline is the metered lane).
        rateLimiter.consume("ai-concept", p.userId().toString());
        return conceptSet.create(p, body);
    }

    @PostMapping("/concept/regenerate")
    public ConceptResponse regenerate(@CurrentUser AuthPrincipal p,
                                      @Valid @RequestBody ConceptRegenerateRequest body) {
        rateLimiter.consume("ai-concept", p.userId().toString());
        aiQuota.checkAndRecordImage(p);
        return studio.regenerate(p, body.conceptId(),
                body.lock() == null ? java.util.List.of() : body.lock());
    }
}
