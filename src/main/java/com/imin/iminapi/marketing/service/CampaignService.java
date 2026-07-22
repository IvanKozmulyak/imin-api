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
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.render.CampaignEmailRenderer;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.template.ResolvedTemplate;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
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
    /** Read-only, org-scoped: resolves the recipient log's Person column display names. */
    private final com.imin.iminapi.audience.repository.MembershipRepository memberships;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    // Test-send now renders through the SAME branded shell the real batch send uses
    // (CampaignEmailRenderer + resolved template), so a preview matches the delivered mail.
    private final CampaignEmailRenderer renderer;
    private final CampaignTemplateService templateService;
    private final OrganizationRepository organizations;
    private final EventRepository events;
    private final MarketingEmailProperties emailProps;

    public CampaignService(CampaignRepository campaigns,
                           com.imin.iminapi.marketing.repository.CampaignRecipientRepository campaignRecipientRepository,
                           AuditLogger audit,
                           SegmentService segments, SendGateService sendGate,
                           EmailService email, UserRepository users,
                           CampaignAttributionService attribution,
                           com.imin.iminapi.audience.repository.MembershipRepository memberships,
                           org.springframework.context.ApplicationEventPublisher eventPublisher,
                           CampaignEmailRenderer renderer, CampaignTemplateService templateService,
                           OrganizationRepository organizations, EventRepository events,
                           MarketingEmailProperties emailProps) {
        this.campaigns = campaigns;
        this.campaignRecipientRepository = campaignRecipientRepository;
        this.audit = audit;
        this.segments = segments;
        this.sendGate = sendGate;
        this.email = email;
        this.users = users;
        this.attribution = attribution;
        this.memberships = memberships;
        this.eventPublisher = eventPublisher;
        this.renderer = renderer;
        this.templateService = templateService;
        this.organizations = organizations;
        this.events = events;
        this.emailProps = emailProps;
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
        c.setTemplateKey(normalizeTemplateKey(req.templateKey()));
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
        Campaign c = require(p.orgId(), id);
        return CampaignDto.from(c, attributedRevenue(p.orgId(), c));
    }

    @Transactional(readOnly = true)
    public List<CampaignSummary> list(AuthPrincipal p, String channel, String status, int page, int size) {
        List<Campaign> rows = campaigns.listByOrg(p.orgId(),
                blankToNull(channel), blankToNull(status), PageRequest.of(page, size));
        // Attributed revenue for the whole page in ONE query (V62) — never per row.
        // Only sent campaigns can have any; drafts are left null (see CampaignSummary.revMinor).
        List<UUID> sentIds = rows.stream().filter(CampaignService::hasSent).map(Campaign::getId).toList();
        var revByCampaign = attribution.attributedRevenueMinorByCampaign(p.orgId(), sentIds);
        return rows.stream()
                .map(c -> CampaignSummary.from(c, hasSent(c) ? revByCampaign.getOrDefault(c.getId(), 0L) : null))
                .toList();
    }

    /**
     * Attributed revenue for one campaign, or null when the campaign has never sent.
     * Null is not "unknown" — it is "not applicable": no link carrying this campaign's
     * utm_campaign exists yet, so no order can be attributed to it. Reporting 0 there would
     * assert the campaign earned nothing, which is a different (and false) claim.
     */
    private Long attributedRevenue(UUID orgId, Campaign c) {
        return hasSent(c) ? attribution.attributedRevenueMinor(orgId, c.getId()) : null;
    }

    /**
     * True once ANY of the campaign's links could be in a buyer's inbox — that is the moment
     * attributed revenue becomes a real (possibly 0) answer rather than "not applicable".
     *
     * <p>Deliberately includes {@code sending}: EmailChannelSender delivers in batches while
     * the status is still 'sending', and {@code sentAt} is only stamped when the whole send
     * COMPLETES (CampaignSendUnit). Keying purely off {@code sentAt} would show an em-dash on
     * a half-sent campaign that is already driving real orders.
     */
    private static boolean hasSent(Campaign c) {
        return c.getSentAt() != null
                || "sent".equals(c.getStatus())
                || "sending".equals(c.getStatus());
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
        if (req.templateKey() != null) c.setTemplateKey(normalizeTemplateKey(req.templateKey()));
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
        copy.setTemplateKey(src.getTemplateKey());
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
                    new PreviewAudienceResponse.Excluded(0, 0, 0, 0, 0, 0));
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
        CampaignEmailRenderer.Rendered rendered = renderForTest(c);
        // Send BOTH parts (html + text) through the same shape the real batch send uses, so a
        // client shows the branded HTML — not a plain-text fallback.
        email.send(to, "[TEST] " + c.getSubject(), rendered.html(), rendered.text());
        audit.record(p, "CAMPAIGN_TEST_SENT", "campaign", c.getId(),
                "Test email sent to organizer");
    }

    /**
     * Render a test send through the EXACT same {@link CampaignEmailRenderer} path as the real
     * batch send ({@code EmailChannelSender}): resolved template shell, brand header, poster hero,
     * {@code {{tickets_button}}} CTA, and the mandatory unsubscribe footer. There is no recipient
     * row for a self-test, so a preview unsubscribe token stands in for the per-recipient signed
     * one — the footer is still present and honest, it just resolves to a preview optout.
     */
    private CampaignEmailRenderer.Rendered renderForTest(Campaign c) {
        // resolve() coalesces null/blank/unknown template_key to the classic builtin, so a pre-V66
        // or NULL row still renders inside the branded shell rather than as bare text.
        ResolvedTemplate template = templateService.resolve(c.getOrgId(), c.getTemplateKey());
        String brandName = brandName(c.getOrgId());
        Event event = linkedEvent(c);
        String posterUrl = event == null ? null : event.getPosterUrl();
        String ticketsUrl = event == null ? null : emailProps.getUnsubscribeBaseUrl() + "/e/" + event.getId();
        String unsubUrl = emailProps.getUnsubscribeBaseUrl() + "/optout?token=preview";
        // A test send has no recipient, so resolve merge tags to a preview sample: the
        // neutral first-name fallback and the real event URL — so the tester sees resolved
        // text, not raw {{firstName}}/{{eventUrl}} tokens.
        String eventUrl = ticketsUrl != null ? ticketsUrl : emailProps.getUnsubscribeBaseUrl();
        String subject = com.imin.iminapi.marketing.render.MergeTags.apply(
                c.getSubject(), com.imin.iminapi.marketing.render.MergeTags.FIRST_NAME_FALLBACK, eventUrl);
        String bodyMd = com.imin.iminapi.marketing.render.MergeTags.apply(
                c.getBodyMd(), com.imin.iminapi.marketing.render.MergeTags.FIRST_NAME_FALLBACK, eventUrl);
        String preheader = com.imin.iminapi.marketing.render.MergeTags.apply(
                c.getPreheader(), com.imin.iminapi.marketing.render.MergeTags.FIRST_NAME_FALLBACK, eventUrl);
        return renderer.render(
                subject, preheader, bodyMd,
                c.getId().toString(), "email", unsubUrl,
                template, brandName, posterUrl, ticketsUrl);
    }

    /** Org brand/display name for the test header. Failure-isolated — a hiccup just omits it. */
    private String brandName(UUID orgId) {
        try {
            Organization org = organizations.findById(orgId).orElse(null);
            if (org == null) return null;
            return org.getBrandName() != null && !org.getBrandName().isBlank()
                    ? org.getBrandName() : org.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** The campaign's linked, org-scoped, active event (or null). Failure-isolated. */
    private Event linkedEvent(Campaign c) {
        if (c.getEventId() == null) return null;
        try {
            return events.findActive(c.getEventId())
                    .filter(e -> c.getOrgId().equals(e.getOrgId()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
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
        Campaign c = campaigns.findByIdAndOrgId(campaignId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Campaign"));
        Instant when = scheduledAt != null ? scheduledAt : Instant.now();
        int updated = campaigns.markScheduledIfDraft(campaignId, principal.orgId(), when);
        if (updated == 0) {
            // Not in draft — duplicate/concurrent send. 409; dispatcher is the sole `sending` path.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Campaign is not in draft");
        }
        // Predictor trigger (task §4): a campaign scheduled for an event → re-forecast.
        // AFTER_COMMIT + debounced in ReforecastTriggerService.
        if (eventPublisher != null && c.getEventId() != null) {
            eventPublisher.publishEvent(
                    new com.imin.iminapi.predictor.service.PredictorMarketingEvents.CampaignScheduled(c.getEventId()));
        }
    }

    /** Engagement axis of the recipient log — derived from timestamps, NOT from the status enum. */
    private enum Engagement { OPENED, CLICKED }

    /**
     * One page of a campaign's recipient log, plus honest counts (spec §2.4).
     *
     * <p>Two orthogonal filter axes:
     * <ul>
     *   <li>{@code status} — lifecycle. Comma-separated for multi-status chips ("Issues" =
     *       {@code bounced,failed,complained}); a single value is just a one-element list, so the
     *       pre-existing {@code ?status=skipped} call keeps working unchanged.</li>
     *   <li>{@code engagement} — {@code opened} | {@code clicked}, read off
     *       {@code opened_at}/{@code clicked_at}. Independent of status by design: a row can be
     *       {@code delivered} AND opened, so these are NOT status values.</li>
     * </ul>
     *
     * <p>{@code total} is a real aggregate over the ACTIVE filter (so the client pages the current
     * subset honestly); {@code counts} is a real aggregate over the WHOLE log (so the chips are
     * true no matter which page is loaded). Neither is derived from {@code items}.
     *
     * <p>The Pageable carries an explicit ascending id sort: Postgres guarantees no row order
     * without an ORDER BY, so an unsorted offset page can silently skip or repeat rows — which
     * would make the new {@code total} authoritative-looking over incoherent paging.
     */
    @Transactional(readOnly = true)
    public com.imin.iminapi.marketing.dto.RecipientPage listRecipients(
            UUID campaignId, AuthPrincipal principal,
            String status, String engagement, int page, int size) {
        campaigns.findByIdAndOrgId(campaignId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Campaign"));

        List<String> statuses = parseStatuses(status);
        Engagement eng = parseEngagement(engagement);

        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.ASC, "id"));

        List<com.imin.iminapi.marketing.model.CampaignRecipient> rows;
        long total;
        if (statuses == null) {
            if (eng == Engagement.OPENED) {
                rows = campaignRecipientRepository.findByCampaignIdAndOpenedAtNotNull(campaignId, pageable);
                total = campaignRecipientRepository.countByCampaignIdAndOpenedAtNotNull(campaignId);
            } else if (eng == Engagement.CLICKED) {
                rows = campaignRecipientRepository.findByCampaignIdAndClickedAtNotNull(campaignId, pageable);
                total = campaignRecipientRepository.countByCampaignIdAndClickedAtNotNull(campaignId);
            } else {
                rows = campaignRecipientRepository.findByCampaignId(campaignId, pageable);
                total = campaignRecipientRepository.countByCampaignId(campaignId);
            }
        } else if (eng == Engagement.OPENED) {
            rows = campaignRecipientRepository.findByCampaignIdAndStatusInAndOpenedAtNotNull(campaignId, statuses, pageable);
            total = campaignRecipientRepository.countByCampaignIdAndStatusInAndOpenedAtNotNull(campaignId, statuses);
        } else if (eng == Engagement.CLICKED) {
            rows = campaignRecipientRepository.findByCampaignIdAndStatusInAndClickedAtNotNull(campaignId, statuses, pageable);
            total = campaignRecipientRepository.countByCampaignIdAndStatusInAndClickedAtNotNull(campaignId, statuses);
        } else {
            rows = campaignRecipientRepository.findByCampaignIdAndStatusIn(campaignId, statuses, pageable);
            total = campaignRecipientRepository.countByCampaignIdAndStatusIn(campaignId, statuses);
        }

        java.util.Map<UUID, String> names = displayNames(principal.orgId(), rows);
        List<com.imin.iminapi.marketing.dto.RecipientDto> items = rows.stream()
                .map(r -> com.imin.iminapi.marketing.dto.RecipientDto.from(r, displayName(names, r)))
                .toList();

        return new com.imin.iminapi.marketing.dto.RecipientPage(items, page, size, total,
                campaignRecipientRepository.chipCounts(campaignId));
    }

    /**
     * Name for one row, or null. The null-membership branch is EXPLICIT rather than leaning on
     * {@code Map.get(null)} returning null: that holds for HashMap but {@code Map.of()} — which
     * {@link #displayNames} returns when a page has no memberships at all — throws NPE on a null
     * key. A DSAR-erased row must degrade to a null name, never take the page down with it.
     */
    private static String displayName(java.util.Map<UUID, String> names,
                                      com.imin.iminapi.marketing.model.CampaignRecipient r) {
        return r.getMembershipId() == null ? null : names.get(r.getMembershipId());
    }

    /**
     * Batch-resolve display names for the loaded page — one org-scoped query, never N+1.
     * A row degrades to a null name (never a placeholder, never an exception) when its
     * membership was DSAR-erased ({@code membership_id} nulled by V53's ON DELETE SET NULL),
     * when the membership carries no display name, or when the id resolves outside the
     * caller's org.
     */
    private java.util.Map<UUID, String> displayNames(
            UUID orgId, List<com.imin.iminapi.marketing.model.CampaignRecipient> rows) {
        java.util.Set<UUID> ids = rows.stream()
                .map(com.imin.iminapi.marketing.model.CampaignRecipient::getMembershipId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return java.util.Map.of();
        return memberships.findByIdsAndOrgId(ids, orgId).stream()
                // Collectors.toMap throws on a null value — a nameless membership must simply be absent.
                .filter(m -> m.getDisplayName() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Membership::getMembershipId, Membership::getDisplayName));
    }

    /** {@code null} when absent/blank; otherwise the CSV split, blanks dropped. */
    private List<String> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<String> out = java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        return out.isEmpty() ? null : out;
    }

    /**
     * Strict: an unrecognised engagement value is a 400, not a silently ignored filter — a chip
     * that quietly returned the unfiltered log would be exactly the authoritative-looking lie
     * this endpoint exists to remove.
     */
    private Engagement parseEngagement(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "opened" -> Engagement.OPENED;
            case "clicked" -> Engagement.CLICKED;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "engagement must be one of: opened, clicked");
        };
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

    /**
     * Draft-only hard delete (Task B3). 404 (no-leak) via {@link #require} when the campaign
     * doesn't exist or belongs to another org; 409 INVALID_STATE for any non-draft status —
     * mirroring the guard style in {@link #cancel} / {@link #patch}. Recipient rows are removed
     * first to keep the FK order safe regardless of the DB-level ON DELETE CASCADE (drafts
     * should have none — this is defensive).
     */
    @Transactional
    public void delete(AuthPrincipal principal, UUID campaignId) {
        Campaign c = require(principal.orgId(), campaignId);
        if (!"draft".equals(c.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only draft campaigns can be deleted");
        }
        campaignRecipientRepository.deleteByCampaignId(campaignId);
        campaigns.delete(c);
        audit.record(principal, AuditActions.CAMPAIGN_DELETED, "campaign", campaignId,
                "Draft campaign deleted");
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
     * A blank/absent template key becomes 'classic' (the default builtin). The value is NOT
     * validated against the builtin/UUID set here on purpose — the renderer resolves an
     * unknown key to the classic fallback (CampaignTemplateService#resolve), so a stale saved
     * template that was later deleted still sends, just in the default shell. Keeping it lenient
     * avoids a write-time coupling to org-template existence.
     */
    private static String normalizeTemplateKey(String raw) {
        return (raw == null || raw.isBlank()) ? "classic" : raw.trim();
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
