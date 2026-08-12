package com.imin.iminapi.audience.controller;

import com.imin.iminapi.audience.dto.*;
import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Audience tab REST controller.
 * Base path: /api/v1/audience
 * No {orgId} in any path — orgId comes ONLY from auth context (SPINE INVARIANT 1).
 */
@RestController
@RequestMapping("/api/v1/audience")
public class AudienceController {

    private final AudienceService audienceService;
    private final AudienceMetricsService metricsService;
    private final ConsentService consentService;
    private final SegmentService segmentService;
    private final SendGateService sendGateService;
    private final DsarService dsarService;
    private final ConsentRecordRepository consentRepo;
    private final SuppressionRepository suppressionRepo;

    public AudienceController(AudienceService audienceService,
                               AudienceMetricsService metricsService,
                               ConsentService consentService,
                               SegmentService segmentService,
                               SendGateService sendGateService,
                               DsarService dsarService,
                               ConsentRecordRepository consentRepo,
                               SuppressionRepository suppressionRepo) {
        this.audienceService = audienceService;
        this.metricsService = metricsService;
        this.consentService = consentService;
        this.segmentService = segmentService;
        this.sendGateService = sendGateService;
        this.dsarService = dsarService;
        this.consentRepo = consentRepo;
        this.suppressionRepo = suppressionRepo;
    }

    // ── Members ────────────────────────────────────────────────────────────

    @GetMapping("/members")
    public MemberPage listMembers(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort) {
        return audienceService.listMembers(principal.orgId(), cursor, limit, lifecycle, search);
    }

    /** CSV export: GET /members?format=csv — routed only when format=csv is present. */
    @GetMapping(value = "/members", params = "format=csv")
    public ResponseEntity<String> exportMembersCsv(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) String search) {
        List<MemberDto> members = audienceService.exportMembersCsv(principal.orgId(), lifecycle, search);
        String csv = buildMembersCsv(members);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audience-members.csv\"")
                .body(csv);
    }

    @GetMapping("/members/{id}")
    public MemberDto getMember(@AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID id) {
        return audienceService.getMember(principal.orgId(), id);
    }

    @GetMapping("/metrics")
    public AudienceMetricsDto metrics(@AuthenticationPrincipal AuthPrincipal principal) {
        return metricsService.compute(principal.orgId());
    }

    // ── Segments ───────────────────────────────────────────────────────────

    @GetMapping("/segments")
    public List<SegmentDto> listSegments(@AuthenticationPrincipal AuthPrincipal principal) {
        // Lazily provision the org's prebuilt segments on first read. Prebuilts used to be
        // seeded only from tests, so production orgs could have zero segments forever — the
        // "sometimes there are no segments" half of this bug. Idempotent + concurrency-safe
        // (see SegmentService.ensurePrebuiltSegments).
        segmentService.ensurePrebuiltSegments(principal.orgId());
        return segmentService.listSegments(principal.orgId()).stream()
                .map(s -> {
                    int liveCount = segmentService.resolveMembers(principal.orgId(), s).size();
                    return SegmentDto.from(s, liveCount);
                }).toList();
    }

    @PostMapping("/segments")
    public ResponseEntity<SegmentDto> createSegment(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateSegmentRequest body) {
        Segment s = segmentService.createSegment(principal.orgId(),
                body.name(), body.kindOrDefault(),
                body.rulesJsonAsString(), principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SegmentDto.from(s, 0));
    }

    @DeleteMapping("/segments/{id}")
    public ResponseEntity<Void> deleteSegment(@AuthenticationPrincipal AuthPrincipal principal,
                                               @PathVariable UUID id) {
        segmentService.deleteSegment(principal.orgId(), id, principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/segments/{id}/snapshot")
    public SegmentDto snapshot(@AuthenticationPrincipal AuthPrincipal principal,
                                @PathVariable UUID id) {
        Segment s = segmentService.snapshot(principal.orgId(), id, principal);
        return SegmentDto.from(s, segmentService.resolveMembers(principal.orgId(), s).size());
    }

    /** CSV snapshot export: GET /segments/{id}/snapshot — returns the segment's resolved members as CSV. */
    @GetMapping(value = "/segments/{id}/snapshot")
    public ResponseEntity<String> exportSnapshotCsv(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id) {
        Segment s = segmentService.requireSegmentForOrg(principal.orgId(), id);
        List<Membership> memberships = segmentService.resolveMembers(principal.orgId(), s);
        List<MemberDto> dtos = audienceService.toMemberDtos(principal.orgId(), memberships);
        String csv = buildMembersCsv(dtos);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"segment-" + id + ".csv\"")
                .body(csv);
    }

    @GetMapping("/segments/{id}/resolve")
    public SegmentResolveDto resolve(@AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable UUID id) {
        return segmentService.resolve(principal.orgId(), id);
    }

    // ── Handoff (FR-SND-1 gate) ────────────────────────────────────────────

    @PostMapping("/segments/{id}/handoff")
    public HandoffResponse segmentHandoff(@AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable UUID id) {
        Segment s = segmentService.listSegments(principal.orgId()).stream()
                .filter(seg -> seg.getId().equals(id)).findFirst()
                .orElseThrow(() -> com.imin.iminapi.security.ApiException.notFound("Segment"));
        List<UUID> memberIds = segmentService.resolveMembers(principal.orgId(), s).stream()
                .map(com.imin.iminapi.audience.model.Membership::getMembershipId).toList();
        return sendGateService.handoff(principal.orgId(), memberIds, principal);
    }

    @PostMapping("/handoff")
    public HandoffResponse handoff(@AuthenticationPrincipal AuthPrincipal principal,
                                   @RequestBody HandoffRequest body) {
        return sendGateService.handoff(principal.orgId(), body.membershipIds(), principal);
    }

    // ── Bulk action ────────────────────────────────────────────────────────

    @PostMapping("/members/bulk-action")
    public ResponseEntity<Void> bulkAction(@AuthenticationPrincipal AuthPrincipal principal,
                                            @RequestBody Map<String, Object> body) {
        // Tier C stub — tag/export/hand-to-marketing wired in Tier D
        return ResponseEntity.ok().build();
    }

    // ── Suppression ────────────────────────────────────────────────────────

    @GetMapping("/suppression")
    public List<SuppressionEntry> getSuppression(@AuthenticationPrincipal AuthPrincipal principal) {
        return suppressionRepo.findMarketingByOrg(principal.orgId());
    }

    // ── Consent ────────────────────────────────────────────────────────────

    @PostMapping("/consent/capture")
    public ResponseEntity<Void> captureConsent(@AuthenticationPrincipal AuthPrincipal principal,
                                                @RequestBody ConsentRequest body) {
        consentService.capture(principal.orgId(), body.membershipId(), body.basis(),
                body.source(), body.proofText(), "email", principal);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/consent/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@AuthenticationPrincipal AuthPrincipal principal,
                                             @RequestBody UnsubRequest body) {
        // OPERATOR, always. This is an organizer removing someone from their own list,
        // and `body.source()` is arbitrary unvalidated request-body text — the whole
        // reason origin is a parameter the endpoint chooses rather than something
        // inferred from the string (§16 / ConsentOrigin). No sticky row: an organizer
        // tidying their audience must not bar that buyer from ever opting back in.
        consentService.unsubscribe(principal.orgId(), body.membershipId(), body.source(), "email",
                ConsentOrigin.OPERATOR, principal);
        return ResponseEntity.ok().build();
    }

    // ── DSAR ───────────────────────────────────────────────────────────────

    @PostMapping("/members/{id}/access")
    public MemberDto dsarAccess(@AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID id) {
        Membership m = dsarService.access(principal.orgId(), id, principal);
        return audienceService.getMember(principal.orgId(), id);
    }

    @PostMapping("/members/{id}/export")
    public MemberDto dsarExport(@AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID id) {
        Membership m = dsarService.export(principal.orgId(), id, principal);
        return audienceService.getMember(principal.orgId(), id);
    }

    @PostMapping("/members/{id}/rectify")
    public MemberDto dsarRectify(@AuthenticationPrincipal AuthPrincipal principal,
                                  @PathVariable UUID id,
                                  @RequestBody RectifyRequest body) {
        dsarService.rectify(principal.orgId(), id, body.displayName(), body.city(), body.notes(), principal);
        return audienceService.getMember(principal.orgId(), id);
    }

    @PostMapping("/members/{id}/erase")
    public ResponseEntity<Void> dsarErase(@AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable UUID id) {
        dsarService.requestErase(principal.orgId(), id, principal);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/members/{id}/object")
    public ResponseEntity<Void> dsarObject(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable UUID id) {
        dsarService.object(principal.orgId(), id, principal);
        return ResponseEntity.ok().build();
    }

    // ── CSV helpers ────────────────────────────────────────────────────────

    /**
     * Renders a list of MemberDto as RFC4180 CSV.
     * Every field is double-quoted; internal double-quotes are doubled.
     * Fields starting with =, +, -, @ are prefixed with a single quote to
     * prevent CSV injection in spreadsheet applications.
     */
    private static String buildMembersCsv(List<MemberDto> members) {
        StringBuilder sb = new StringBuilder(members.size() * 120 + 256);
        // Header row
        sb.append("\"name\",\"email\",\"city\",\"lifecycle\",\"events\",\"attended\"," +
                  "\"noShow\",\"orders\",\"spend\",\"recencyDays\",\"subscriptionStatus\"," +
                  "\"lawfulBasis\",\"firstTouchSource\",\"tags\",\"nps\"\r\n");
        for (MemberDto m : members) {
            sb.append(csvField(m.name())).append(',');
            sb.append(csvField(m.email())).append(',');
            sb.append(csvField(m.city())).append(',');
            sb.append(csvField(m.lifecycle())).append(',');
            sb.append(csvField(String.valueOf(m.events()))).append(',');
            sb.append(csvField(String.valueOf(m.attended()))).append(',');
            sb.append(csvField(String.valueOf(m.noShow()))).append(',');
            sb.append(csvField(String.valueOf(m.orders()))).append(',');
            // spend in euros, 2 decimal places
            sb.append(csvField(String.format("%.2f", m.spendMinor() / 100.0))).append(',');
            sb.append(csvField(m.recencyDays() == null ? "" : String.valueOf(m.recencyDays()))).append(',');
            sb.append(csvField(m.subscriptionStatus())).append(',');
            sb.append(csvField(m.lawfulBasis())).append(',');
            sb.append(csvField(m.firstTouchSource())).append(',');
            // tags: semicolon-joined
            String tags = m.tags() == null ? "" : String.join(";", m.tags());
            sb.append(csvField(tags)).append(',');
            sb.append(csvField(m.nps() == null ? "" : String.valueOf(m.nps())));
            sb.append("\r\n");
        }
        return sb.toString();
    }

    /** RFC4180 quoting + CSV injection guard. Null/empty → empty quoted field. */
    private static String csvField(String value) {
        if (value == null) value = "";
        // CSV injection guard: prefix formula-starting chars with a single quote
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        // Escape internal double-quotes by doubling them
        value = value.replace("\"", "\"\"");
        return "\"" + value + "\"";
    }

    // ── Request body records ───────────────────────────────────────────────

    record HandoffRequest(java.util.List<UUID> membershipIds) {}
    record ConsentRequest(UUID membershipId, String basis, String source, String proofText) {}
    record UnsubRequest(UUID membershipId, String source) {}
    record RectifyRequest(String displayName, String city, String notes) {}
}
