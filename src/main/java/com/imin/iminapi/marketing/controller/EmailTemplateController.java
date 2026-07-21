package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.EmailTemplateDto;
import com.imin.iminapi.marketing.dto.GenerateTemplateRequest;
import com.imin.iminapi.marketing.service.CampaignTemplateService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Campaign email templates (spec §5). Base path {@code /api/v1/marketing/email-templates};
 * no {orgId} — org comes ONLY from the auth context.
 *
 * <ul>
 *   <li>{@code GET} — list the four builtins + the org's saved templates.</li>
 *   <li>{@code POST /generate} — AI-generate + save an org template from org/event context.
 *       Rate-limited on the shared {@code ai-concept} bucket (another authenticated AI text
 *       call), exactly like {@code SubjectVariantsController}.</li>
 *   <li>{@code DELETE /{id}} — delete an org template (404 no-leak; builtins are not rows).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/marketing/email-templates")
public class EmailTemplateController {

    private final CampaignTemplateService service;
    private final RateLimiter rateLimiter;

    public EmailTemplateController(CampaignTemplateService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public List<EmailTemplateDto> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.list(principal);
    }

    @PostMapping("/generate")
    public EmailTemplateDto generate(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody(required = false) GenerateTemplateRequest body) {
        rateLimiter.consume("ai-concept", principal.userId().toString());
        return service.generate(principal, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        service.delete(principal, id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
