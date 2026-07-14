package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Marketing campaign CRUD + duplicate + (Task 5) preview-audience + test-send.
 * Org comes ONLY from the AuthPrincipal — never a path/body var (SPINE INVARIANT).
 */
@Service
public class CampaignService {

    static final Set<String> CHANNELS = Set.of("email", "sms");
    private static final int MAX_NAME = 120;

    private final CampaignRepository campaigns;
    private final com.imin.iminapi.marketing.repository.CampaignRecipientRepository campaignRecipientRepository;
    private final AuditLogger audit;
    private final SegmentService segments;
    private final SendGateService sendGate;
    private final EmailService email;
    private final UserRepository users;
    private final CampaignAttributionService attribution;

    public CampaignService(CampaignRepository campaigns,
                           com.imin.iminapi.marketing.repository.CampaignRecipientRepository campaignRecipientRepository,
                           AuditLogger audit,
                           SegmentService segments, SendGateService sendGate,
                           EmailService email, UserRepository users,
                           CampaignAttributionService attribution) {
        this.campaigns = campaigns;
        this.campaignRecipientRepository = campaignRecipientRepository;
        this.audit = audit;
        this.segments = segments;
        this.sendGate = sendGate;
        this.email = email;
        this.users = users;
        this.attribution = attribution;
    }

    @Transactional
    public CampaignDto create(AuthPrincipal p, CreateCampaignRequest req) {
        String name = requireName(req.name());
        String channel = requireChannel(req.channel());
        Instant now = Instant.now();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(p.orgId());
        c.setChannel(channel);
        c.setName(name);
        c.setStatus("draft");
        c.setOrigin("manual");
        c.setSegmentId(req.segmentId());
        c.setEventId(req.eventId());
        c.setSubject(req.subject());
        c.setPreheader(req.preheader());
        c.setBodyMd(req.bodyMd());
        c.setCreatedBy(p.userId());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        Campaign saved = campaigns.save(c);
        audit.record(p, AuditActions.CAMPAIGN_CREATED, "campaign", saved.getId(),
                "Campaign created: " + name + " (" + channel + ")");
        return CampaignDto.from(saved);
    }

    @Transactional(readOnly = true)
    public CampaignDto get(AuthPrincipal p, UUID id) {
        return CampaignDto.from(require(p.orgId(), id));
    }

    @Transactional(readOnly = true)
    public List<CampaignSummary> list(AuthPrincipal p, String channel, String status, int page, int size) {
        List<Campaign> rows = campaigns.listByOrg(p.orgId(),
                blankToNull(channel), blankToNull(status), PageRequest.of(page, size));
        return rows.stream().map(CampaignSummary::from).toList();
    }

    @Transactional
    public CampaignDto patch(AuthPrincipal p, UUID id, PatchCampaignRequest req) {
        Campaign c = require(p.orgId(), id);
        if (!"draft".equals(c.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only draft campaigns can be edited");
        }
        if (req.name() != null) c.setName(requireName(req.name()));
        if (req.segmentId() != null) c.setSegmentId(req.segmentId());
        if (req.eventId() != null) c.setEventId(req.eventId());
        if (req.subject() != null) c.setSubject(req.subject());
        if (req.preheader() != null) c.setPreheader(req.preheader());
        if (req.bodyMd() != null) c.setBodyMd(req.bodyMd());
        c.setUpdatedAt(Instant.now());
        return CampaignDto.from(campaigns.save(c));
    }

    @Transactional
    public CampaignDto duplicate(AuthPrincipal p, UUID id) {
        Campaign src = require(p.orgId(), id);
        Instant now = Instant.now();
        Campaign copy = new Campaign();
        copy.setId(UUID.randomUUID());
        copy.setOrgId(src.getOrgId());
        copy.setChannel(src.getChannel());
        copy.setName(truncateName(src.getName() + " (copy)"));
        copy.setStatus("draft");
        copy.setOrigin("manual");
        copy.setSegmentId(src.getSegmentId());
        copy.setEventId(src.getEventId());
        copy.setSubject(src.getSubject());
        copy.setPreheader(src.getPreheader());
        copy.setBodyMd(src.getBodyMd());
        copy.setBodyTemplate(src.getBodyTemplate());
        copy.setSenderId(src.getSenderId());
        copy.setCreatedBy(p.userId());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        Campaign saved = campaigns.save(copy);
        audit.record(p, AuditActions.CAMPAIGN_DUPLICATED, "campaign", saved.getId(),
                "Duplicated from " + src.getId());
        return CampaignDto.from(saved);
    }

    /** SendGate dry-run for the composer Audience step. No materialization, no send. */
    @Transactional(readOnly = true)
    public PreviewAudienceResponse previewAudience(AuthPrincipal p, UUID id) {
        Campaign c = require(p.orgId(), id);
        if (c.getSegmentId() == null) {
            // no target yet: nothing sendable, nothing excluded
            return new PreviewAudienceResponse(0,
                    new PreviewAudienceResponse.Excluded(0, 0, 0, 0, 0));
        }
        Segment segment = segments.requireSegmentForOrg(p.orgId(), c.getSegmentId());
        List<UUID> membershipIds = segments.resolveMembers(p.orgId(), segment).stream()
                .map(Membership::getMembershipId).toList();
        return sendGate.previewCounts(p.orgId(), membershipIds, c.getChannel());
    }

    /**
     * Send a single test email of the draft to the authenticated organizer's own address.
     * Restricted to the caller (spec §3/§7) — a requested address is only honored if it
     * equals the caller's own; otherwise the caller's address is used unconditionally.
     */
    @Transactional(readOnly = true)
    public void testSend(AuthPrincipal p, UUID id, String requestedEmail) {
        Campaign c = require(p.orgId(), id);
        if (!"email".equals(c.getChannel())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_STATE,
                    "Only email campaigns can be test-sent");
        }
        if (c.getSubject() == null || c.getSubject().isBlank()
                || c.getBodyMd() == null || c.getBodyMd().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "Subject and body are required to send a test");
        }
        String to = callerEmail(p);   // always the organizer — requestedEmail is advisory only
        String html = renderTestHtml(c);
        String text = c.getBodyMd();
        email.send(to, "[TEST] " + c.getSubject(), html, text);
        audit.record(p, "CAMPAIGN_TEST_SENT", "campaign", c.getId(),
                "Test email sent to organizer");
    }

    private String renderTestHtml(Campaign c) {
        // Phase-1 test-send preview: escape the body so the test send is never an injection
        // vector (spec §3). Real branded-shell rendering ships in Phase 2's CampaignEmailProvider.
        String safe = c.getBodyMd()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String pre = c.getPreheader() == null ? "" :
                "<p style=\"color:#888\">" + c.getPreheader()
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>";
        return "<html><body>" + pre + "<pre style=\"font-family:inherit;white-space:pre-wrap\">"
                + safe + "</pre></body></html>";
    }

    private String callerEmail(AuthPrincipal p) {
        // AuthPrincipal carries no email (AuthPrincipal.java:28) — load the organizer's user row.
        User u = users.findById(p.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                        "Caller has no user record"));
        return u.getEmail();
    }

    /**
     * Spec §2.4: guarded draft→scheduled compare-and-set with a mandatory Idempotency-Key.
     * The guarded transition IS the double-submit guard (imin-api scout §6: the shared
     * idempotency_keys table was DROPPED in V11) — a replay finds status!='draft' and 409s
     * INVALID_STATE, which the FE treats as success-idempotent. The dispatcher is the sole
     * `sending` path; this method only flips draft→scheduled.
     */
    @Transactional
    public void send(UUID campaignId, AuthPrincipal principal,
                     String idempotencyKey, Instant scheduledAt) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_IDEMPOTENCY_KEY,
                    "Idempotency-Key header is required");
        }
        // Org-scope + existence check (404 leak-safe if not this org's campaign).
        campaigns.findByIdAndOrgId(campaignId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Campaign"));
        Instant when = scheduledAt != null ? scheduledAt : Instant.now();
        int updated = campaigns.markScheduledIfDraft(campaignId, principal.orgId(), when);
        if (updated == 0) {
            // Not in draft — duplicate/concurrent send. 409; dispatcher is the sole `sending` path.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Campaign is not in draft");
        }
    }

    @Transactional(readOnly = true)
    public com.imin.iminapi.marketing.dto.RecipientPage listRecipients(
            UUID campaignId, AuthPrincipal principal,
            String status, int page, int size) {
        campaigns.findByIdAndOrgId(campaignId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Campaign"));
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size);
        List<com.imin.iminapi.marketing.model.CampaignRecipient> rows =
                (status == null || status.isBlank())
                        ? campaignRecipientRepository.findByCampaignId(campaignId, pageable)
                        : campaignRecipientRepository.findByCampaignIdAndStatus(campaignId, status, pageable);
        List<com.imin.iminapi.marketing.dto.RecipientDto> items =
                rows.stream().map(com.imin.iminapi.marketing.dto.RecipientDto::from).toList();
        return new com.imin.iminapi.marketing.dto.RecipientPage(items, page, size);
    }

    /**
     * Guarded {@code scheduled→canceled} (spec §2.4). Any other status is a 409 INVALID_STATE —
     * a sending/sent/failed/draft campaign cannot be canceled through this endpoint.
     */
    @Transactional
    public void cancel(AuthPrincipal principal, UUID campaignId) {
        Campaign c = require(principal.orgId(), campaignId);
        if (!"scheduled".equals(c.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only scheduled campaigns can be canceled");
        }
        c.setStatus("canceled");
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        audit.record(principal, "CAMPAIGN_CANCELED", "campaign", c.getId(), "Campaign canceled");
    }

    /**
     * Guarded retry of a {@code failed} campaign (spec §2.4): flip back to {@code scheduled}
     * with {@code scheduled_at=now} so the dispatcher re-claims it, only while attempts &lt; 3.
     * Any other status — or an exhausted-attempts failed row — is a 409 INVALID_STATE.
     */
    @Transactional
    public Campaign retry(AuthPrincipal principal, UUID campaignId) {
        Campaign c = require(principal.orgId(), campaignId);
        if (!"failed".equals(c.getStatus()) || c.getAttempts() >= 3) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Campaign is not retryable");
        }
        c.setStatus("scheduled");
        c.setScheduledAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        Campaign saved = campaigns.save(c);
        audit.record(principal, "CAMPAIGN_RETRIED", "campaign", c.getId(), "Campaign retry queued");
        return saved;
    }

    /** Campaign detail + aggregate stats block (spec §2.4/§3). Org-scoped. */
    @Transactional(readOnly = true)
    public com.imin.iminapi.marketing.dto.CampaignDetailDto detailWithStats(AuthPrincipal p, UUID id) {
        Campaign c = require(p.orgId(), id);
        return com.imin.iminapi.marketing.dto.CampaignDetailDto.from(c, stats(id));
    }

    /**
     * Aggregate send/engagement counts for a campaign (spec §3). {@code sent} is every row that
     * left the queue (any lifecycle status past pending/failed/skipped); {@code opened}/{@code clicked}
     * read the webhook-projected timestamps; {@code attributedPurchases} is the utm_campaign funnel count.
     */
    @Transactional(readOnly = true)
    public com.imin.iminapi.marketing.dto.CampaignStatsDto stats(UUID campaignId) {
        long sent = campaignRecipientRepository.countByCampaignIdAndStatusIn(campaignId,
                java.util.List.of("sent", "delivered", "opened", "clicked",
                        "bounced", "complained", "unsubscribed"));
        long delivered = campaignRecipientRepository.countByCampaignIdAndStatus(campaignId, "delivered");
        long opened = campaignRecipientRepository.countByCampaignIdAndOpenedAtNotNull(campaignId);
        long clicked = campaignRecipientRepository.countByCampaignIdAndClickedAtNotNull(campaignId);
        long bounced = campaignRecipientRepository.countByCampaignIdAndStatus(campaignId, "bounced");
        long unsubscribed = campaignRecipientRepository.countByCampaignIdAndStatus(campaignId, "unsubscribed");
        long complained = campaignRecipientRepository.countByCampaignIdAndStatus(campaignId, "complained");
        long attributed = attribution.attributedPurchaseCount(campaignId);
        return new com.imin.iminapi.marketing.dto.CampaignStatsDto(
                sent, delivered, opened, clicked, bounced, unsubscribed, complained, attributed);
    }

    // ---- helpers ----

    Campaign require(UUID orgId, UUID id) {
        return campaigns.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                        "Campaign not found"));
    }

    private String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "name is required");
        }
        return truncateName(raw.trim());
    }

    private String truncateName(String s) {
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }

    private String requireChannel(String raw) {
        if (raw == null || !CHANNELS.contains(raw)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "channel must be email or sms");
        }
        return raw;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * TEST-ONLY seam: force a status without lifecycle rules so tests can assert draft-only
     * guards. Never call from production code — Phase 2's dispatcher owns real transitions.
     */
    @Transactional
    public void forceStatusForTest(UUID id, String status) {
        Campaign c = campaigns.findByIdForTest(id)
                .orElseThrow(() -> new IllegalStateException("no campaign " + id));
        c.setStatus(status);
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
    }
}
