package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignRequests.TestSendRequest;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Marketing campaigns REST controller (spec §2.4).
 * Base path: /api/v1/marketing/campaigns. No {orgId} — org comes ONLY from auth context.
 */
@RestController
@RequestMapping("/api/v1/marketing/campaigns")
public class CampaignController {

    private final CampaignService service;

    public CampaignController(CampaignService service) {
        this.service = service;
    }

    @GetMapping
    public List<CampaignSummary> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(principal, channel, status, page, size);
    }

    @PostMapping
    public ResponseEntity<CampaignDto> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody CreateCampaignRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal, req));
    }

    @GetMapping("/{id}")
    public com.imin.iminapi.marketing.dto.CampaignDetailDto get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return service.detailWithStats(principal, id);
    }

    @PatchMapping("/{id}")
    public CampaignDto patch(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id,
            @RequestBody PatchCampaignRequest req) {
        return service.patch(principal, id, req);
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<CampaignDto> duplicate(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.duplicate(principal, id));
    }

    @PostMapping("/{id}/preview-audience")
    public PreviewAudienceResponse previewAudience(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return service.previewAudience(principal, id);
    }

    @PostMapping("/{id}/test-send")
    public ResponseEntity<Void> testSend(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) TestSendRequest req) {
        service.testSend(principal, id, req == null ? null : req.email());
        return ResponseEntity.noContent().build();
    }

    public record SendRequest(java.time.Instant scheduledAt) {}

    @PostMapping("/{id}/send")
    public ResponseEntity<Void> send(
            @PathVariable UUID id,
            @com.imin.iminapi.security.CurrentUser AuthPrincipal principal,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) SendRequest body) {
        java.time.Instant scheduledAt = body == null ? null : body.scheduledAt();
        service.send(id, principal, idempotencyKey, scheduledAt);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /** Guarded scheduled→canceled (spec §2.4). 409 INVALID_STATE from any other status. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        service.cancel(principal, id);
        return ResponseEntity.ok().build();
    }

    /**
     * Retry a failed campaign (spec §2.4). Distinct from {@code /send} (which is the
     * draft→scheduled path) — this re-queues a {@code failed} campaign while attempts &lt; 3.
     * Returns 202 Accepted (the dispatcher re-claims it), matching {@code /send} semantics.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        service.retry(principal, id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * Draft-only delete (Task B3). 204 when the campaign is this org's draft and is removed;
     * 404 (no-leak) when not found or another org's; 409 INVALID_STATE for any non-draft status.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        service.delete(principal, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Recipient log for one campaign — org-scoped via the auth principal (404 no-leak for
     * another org's campaign), unchanged from before.
     *
     * <p>Response is {@code items}/{@code page}/{@code size} (original shape) plus additive
     * {@code total} (real aggregate over the active filter) and {@code counts} (real aggregates
     * over the whole log, for the filter chips).
     *
     * @param status     lifecycle filter; comma-separated for multi-status chips, e.g.
     *                   {@code bounced,failed,complained}. A single value behaves as before.
     * @param engagement {@code opened} | {@code clicked} — an axis ORTHOGONAL to {@code status},
     *                   read from {@code opened_at}/{@code clicked_at}. Combinable with
     *                   {@code status}. Any other value is a 400 FIELD_INVALID.
     */
    @GetMapping("/{id}/recipients")
    public com.imin.iminapi.marketing.dto.RecipientPage recipients(
            @PathVariable UUID id,
            @com.imin.iminapi.security.CurrentUser AuthPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String engagement,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listRecipients(id, principal, status, engagement, page, size);
    }
}
