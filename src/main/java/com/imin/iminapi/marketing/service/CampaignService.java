package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.service.SendPathGuard;
import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignSendResponse;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.dto.RecipientCounts;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignFailureReason;
import com.imin.iminapi.marketing.model.RecipientStatuses;
import com.imin.iminapi.marketing.dto.RenderPreviewRequest;
import com.imin.iminapi.marketing.dto.RenderPreviewResponse;
import com.imin.iminapi.marketing.render.CampaignEmailAssembler;
import com.imin.iminapi.marketing.render.EmailShellCopy;
import com.imin.iminapi.marketing.render.MergeTags;
import com.imin.iminapi.marketing.repository.CampaignRepository;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Marketing campaign CRUD + duplicate + (Task 5) preview-audience + test-send + render-preview.
 * Org comes ONLY from the AuthPrincipal — never a path/body var (SPINE INVARIANT).
 */
@Service
public class CampaignService {

    /** A send is stored this far ahead, so the organizer can still take it back. */
    private static final Duration SEND_UNDO_WINDOW = Duration.ofSeconds(60);
    static final Set<String> CHANNELS = Set.of("email", "sms");
    private static final int MAX_NAME = 120;
    /** Recipient statuses of a row that left the queue: what {@code stats.sent} and the cancel audit count. */
    private static final String CANCELED = "canceled";
    /** A failed campaign can be retried while its attempts are below this (the SQL guard in retryIfFailed mirrors it). */
    static final int MAX_ATTEMPTS = 3;
    private static final List<String> LEFT_THE_QUEUE = RecipientStatuses.LEFT_THE_QUEUE;

    private final CampaignRepository campaigns;
    private final com.imin.iminapi.marketing.repository.CampaignRecipientRepository campaignRecipientRepository;
    private final AuditLogger audit;
    private final SegmentService segments;
    private final SendGateService sendGate;
    private final CampaignEmailProvider provider;
    private final UserRepository users;
    private final CampaignAttributionService attribution;
    /** Read-only, org-scoped: resolves the recipient log's Person column display names. */
    private final com.imin.iminapi.audience.repository.MembershipRepository memberships;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    // Test send and preview assemble the email through the same path as the batch send, so they match the delivered mail.
    private final CampaignEmailAssembler assembler;
    private final OrganizationRepository organizations;
    private final EventRepository events;
    private final MarketingEmailProperties emailProps;
    private final AudiencePlanAccess audiencePlanAccess;
    private final CampaignAiSuggestions aiSuggestions;
    private final SendPathGuard sendPathGuard;
    private final com.imin.iminapi.audienceplan.service.TimingArmScheduler timingArms;
    private final com.imin.iminapi.repository.AuditLogRepository auditLogs;
    private final com.imin.iminapi.marketing.repository.MomentumSuggestionRepository momentumSuggestions;
    private final Clock clock;
    private final QuietHours quietHours;
    private final com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService unsubscribeTokens;
    private final com.imin.iminapi.repository.ModerationHoldRepository moderationHolds;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public CampaignService(CampaignRepository campaigns,
                           com.imin.iminapi.marketing.repository.CampaignRecipientRepository campaignRecipientRepository,
                           AuditLogger audit,
                           SegmentService segments, SendGateService sendGate,
                           CampaignEmailProvider provider, UserRepository users,
                           CampaignAttributionService attribution,
                           com.imin.iminapi.audience.repository.MembershipRepository memberships,
                           org.springframework.context.ApplicationEventPublisher eventPublisher,
                           CampaignEmailAssembler assembler,
                           OrganizationRepository organizations, EventRepository events,
                           MarketingEmailProperties emailProps,
                           AudiencePlanAccess audiencePlanAccess,
                           CampaignAiSuggestions aiSuggestions,
                           SendPathGuard sendPathGuard,
                           com.imin.iminapi.audienceplan.service.TimingArmScheduler timingArms,
                           com.imin.iminapi.repository.AuditLogRepository auditLogs,
                           com.imin.iminapi.marketing.repository.MomentumSuggestionRepository momentumSuggestions,
                           Clock clock, QuietHours quietHours,
                           com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService unsubscribeTokens,
                           com.imin.iminapi.repository.ModerationHoldRepository moderationHolds) {
        this.clock = clock;
        this.moderationHolds = moderationHolds;
        this.quietHours = quietHours;
        this.unsubscribeTokens = unsubscribeTokens;
        this.auditLogs = auditLogs;
        this.momentumSuggestions = momentumSuggestions;
        this.campaigns = campaigns;
        this.campaignRecipientRepository = campaignRecipientRepository;
        this.audit = audit;
        this.segments = segments;
        this.sendGate = sendGate;
        this.provider = provider;
        this.users = users;
        this.attribution = attribution;
        this.memberships = memberships;
        this.eventPublisher = eventPublisher;
        this.assembler = assembler;
        this.organizations = organizations;
        this.events = events;
        this.emailProps = emailProps;
        this.audiencePlanAccess = audiencePlanAccess;
        this.aiSuggestions = aiSuggestions;
        this.sendPathGuard = sendPathGuard;
        this.timingArms = timingArms;
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
        c.setSubjectAiGenerated(Boolean.TRUE.equals(req.subjectAiGenerated()));
        c.setBodyAiGenerated(Boolean.TRUE.equals(req.bodyAiGenerated()));
        c.setLocale(req.locale() != null ? req.locale() : defaultLocale(p));
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
        List<UUID> canceledIds = rows.stream().filter(CampaignService::mayHaveSentThenStopped).map(Campaign::getId).toList();
        Set<UUID> canceledAfterSending = canceledIds.isEmpty() ? Set.of()
                : Set.copyOf(campaignRecipientRepository.findCampaignIdsWithStatusIn(canceledIds, LEFT_THE_QUEUE));
        java.util.function.Predicate<Campaign> sent = c -> hasSent(c) || canceledAfterSending.contains(c.getId());
        List<UUID> sentIds = rows.stream().filter(sent).map(Campaign::getId).toList();
        var revByCampaign = attribution.attributedRevenueMinorByCampaign(p.orgId(), sentIds);
        // Segment names and linked events for the page in one query each, org-scoped like detail.
        var segmentNames = segments.namesByIds(p.orgId(), idsOf(rows, Campaign::getSegmentId));
        var linkedEvents = activeEventsByIds(p.orgId(), idsOf(rows, Campaign::getEventId));
        Map<UUID, CampaignSummary.Counts> counts = outcomeCounts(rows);
        SendHolds holds = rows.stream().anyMatch(CampaignService::waitingToSend) ? sendHolds(p.orgId()) : SendHolds.NONE;
        return rows.stream()
                .map(c -> {
                    Event e = c.getEventId() == null ? null : linkedEvents.get(c.getEventId());
                    return CampaignSummary.from(c,
                            sent.test(c) ? revByCampaign.getOrDefault(c.getId(), 0L) : null,
                            c.getSegmentId() == null ? null : segmentNames.get(c.getSegmentId()),
                            e == null ? null : e.getName(),
                            e == null ? null : e.getTimezone(),
                            CampaignFailureReason.of(c.getLastError()),
                            counts.get(c.getId()),
                            holds.of(c));
                })
                .toList();
    }

    /** sendHold values: the org's marketing sending is paused, or a moderation hold covers the org or the event. */
    static final String HOLD_ORG_PAUSED = "org_paused";
    static final String HOLD_MODERATION = "moderation_hold";

    private static boolean waitingToSend(Campaign c) {
        return "scheduled".equals(c.getStatus()) || "sending".equals(c.getStatus());
    }

    /** The org's pause and its active holds, read once per request, judged per campaign by {@link #of}. */
    private record SendHolds(boolean orgPaused, boolean orgWideHold, Set<UUID> heldEventIds) {
        static final SendHolds NONE = new SendHolds(false, false, Set.of());

        /** Same rule as the claim's SENDABLE predicate; only for a campaign still waiting to send. */
        String of(Campaign c) {
            if (!waitingToSend(c)) return null;
            if (orgPaused) return HOLD_ORG_PAUSED;
            if (orgWideHold || (c.getEventId() != null && heldEventIds.contains(c.getEventId()))) return HOLD_MODERATION;
            return null;
        }
    }

    private SendHolds sendHolds(UUID orgId) {
        Organization org = organizations.findById(orgId).orElse(null);
        boolean paused = org != null && org.getMarketingPausedAt() != null;
        boolean orgWide = false;
        Set<UUID> events = new java.util.HashSet<>();
        for (com.imin.iminapi.model.ModerationHold h : moderationHolds.findActiveByOrgId(orgId)) {
            if (h.getEventId() == null) orgWide = true;
            else events.add(h.getEventId());
        }
        return new SendHolds(paused, orgWide, events);
    }

    /**
     * Live row counts per email campaign of the page with recipient rows, on the definitions the campaign page uses;
     * one query per page.
     */
    private Map<UUID, CampaignSummary.Counts> outcomeCounts(List<Campaign> rows) {
        List<UUID> emailIds = rows.stream().filter(c -> "email".equals(c.getChannel())).map(Campaign::getId).toList();
        if (emailIds.isEmpty()) return Map.of();
        Map<UUID, CampaignSummary.Counts> out = new java.util.HashMap<>();
        for (Object[] r : campaignRecipientRepository.outcomeCountsByCampaign(emailIds)) {
            out.put((UUID) r[0], new CampaignSummary.Counts(n(r[1]), n(r[2]), n(r[3]), n(r[4]), n(r[5]), n(r[6])));
        }
        return out;
    }

    private static long n(Object o) {
        return ((Number) o).longValue();
    }

    private static Set<UUID> idsOf(List<Campaign> rows, Function<Campaign, UUID> id) {
        return rows.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Map<UUID, Event> activeEventsByIds(UUID orgId, Set<UUID> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        return events.findActiveByOrgAndIds(orgId, eventIds).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
    }

    /**
     * Attributed revenue for one campaign, or null when the campaign has never sent.
     * Null is not "unknown" — it is "not applicable": no link carrying this campaign's
     * utm_campaign exists yet, so no order can be attributed to it. Reporting 0 there would
     * assert the campaign earned nothing, which is a different (and false) claim.
     */
    private Long attributedRevenue(UUID orgId, Campaign c) {
        boolean sent = hasSent(c) || (mayHaveSentThenStopped(c)
                && campaignRecipientRepository.countByCampaignIdAndStatusIn(c.getId(), LEFT_THE_QUEUE) > 0);
        return sent ? attribution.attributedRevenueMinor(orgId, c.getId()) : null;
    }

    /**
     * True once ANY of the campaign's links could be in a buyer's inbox — that is the moment
     * attributed revenue becomes a real (possibly 0) answer rather than "not applicable".
     *
     * <p>Deliberately includes {@code sending}: EmailChannelSender delivers in batches while
     * the status is still 'sending', and {@code sentAt} is only stamped when the whole send
     * COMPLETES (CampaignSendUnit). Keying purely off {@code sentAt} would show an em-dash on
     * a half-sent campaign that is already driving real orders. A campaign canceled or failed mid-send is
     * checked against its recipient rows by the callers (one query per page in the list).
     */
    private static boolean mayHaveSentThenStopped(Campaign c) {
        return CANCELED.equals(c.getStatus()) || "failed".equals(c.getStatus());
    }

    private static boolean hasSent(Campaign c) {
        return c.getSentAt() != null
                || "sent".equals(c.getStatus())
                || "sending".equals(c.getStatus());
    }

    @Transactional
    public CampaignDto patch(AuthPrincipal p, UUID id, PatchCampaignRequest req) {
        // Unlocked pre-check first, so a PATCH on a campaign mid-materialize answers 409 instead of waiting out its lock.
        if (!"draft".equals(require(p.orgId(), id).getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only draft campaigns can be edited");
        }
        // Locked until commit, so a send that flips draft→scheduled meanwhile waits rather than being overwritten.
        Campaign c = lockFresh(p.orgId(), id);
        if (!"draft".equals(c.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only draft campaigns can be edited");
        }
        requirePlanTargetKept(c, req);
        String channel = req.channel() == null ? null : requireChannel(req.channel());
        if (AudiencePlanAccess.CAMPAIGN_ORIGIN.equals(c.getOrigin())) timingArms.disarm(c);
        if (req.name() != null) c.setName(requireName(req.name()));
        if (channel != null) c.setChannel(channel);
        // mkt-edge-8: PatchableUuid distinguishes "absent" (the component is null — leave the
        // link alone) from "present and null" (PatchableUuid.NULL — unlink). The composer
        // PATCHes {segmentId: null, eventId: null} when the organizer de-selects, and that
        // used to be indistinguishable from an untouched field, so the campaign kept sending
        // the old event's poster hero and tickets button.
        if (req.segmentId() != null) c.setSegmentId(req.segmentId().value());
        if (req.eventId() != null) c.setEventId(req.eventId().value());
        if (req.subject() != null) c.setSubject(req.subject());
        if (req.preheader() != null) c.setPreheader(req.preheader());
        if (req.bodyMd() != null) c.setBodyMd(req.bodyMd());
        if (req.templateKey() != null) c.setTemplateKey(normalizeTemplateKey(req.templateKey()));
        if (req.locale() != null) c.setLocale(req.locale());
        // AI provenance is sticky: the client's true, or saved text a model offered for this campaign.
        if (Boolean.TRUE.equals(req.subjectAiGenerated())
                || aiSuggestions.wasOffered(c.getId(), CampaignAiSuggestions.SUBJECT, req.subject())) {
            c.setSubjectAiGenerated(true);
        }
        if (Boolean.TRUE.equals(req.bodyAiGenerated())
                || aiSuggestions.wasOffered(c.getId(), CampaignAiSuggestions.BODY, req.bodyMd())
                || aiSuggestions.wasOffered(c.getId(), CampaignAiSuggestions.BODY, req.preheader())) {
            c.setBodyAiGenerated(true);
        }
        c.setUpdatedAt(Instant.now());
        return CampaignDto.from(campaigns.save(c));
    }

    /** A new campaign's language: the org country, else the caller's dashboard language, else English. */
    private String defaultLocale(AuthPrincipal p) {
        Organization org = organizationOrNull(p.orgId());
        String userLocale = users.findById(p.userId()).map(User::getLocale).orElse(null);
        return CampaignLocale.defaultFor(org == null ? null : org.getCountry(), userLocale);
    }

    /** An invitation arm keeps its segment and event, so its experiment link and holdout skip stay intact. */
    private static void requirePlanTargetKept(Campaign c, PatchCampaignRequest req) {
        if (!AudiencePlanAccess.CAMPAIGN_ORIGIN.equals(c.getOrigin())) return;
        boolean segmentChanged = req.segmentId() != null && !Objects.equals(req.segmentId().value(), c.getSegmentId());
        boolean eventChanged = req.eventId() != null && !Objects.equals(req.eventId().value(), c.getEventId());
        boolean channelChanged = req.channel() != null && !req.channel().equals(c.getChannel());
        if (channelChanged) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "The channel of an audience plan campaign cannot be changed");
        }
        if (segmentChanged || eventChanged) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "The segment and event of an audience plan campaign cannot be changed");
        }
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
        // An audience-plan copy keeps its origin, so duplicating cannot bypass the sends switch.
        copy.setOrigin(AudiencePlanAccess.CAMPAIGN_ORIGIN.equals(src.getOrigin())
                ? AudiencePlanAccess.CAMPAIGN_ORIGIN : "manual");
        copy.setSegmentId(src.getSegmentId());
        copy.setEventId(src.getEventId());
        copy.setSubject(src.getSubject());
        copy.setPreheader(src.getPreheader());
        copy.setBodyMd(src.getBodyMd());
        copy.setSubjectAiGenerated(src.isSubjectAiGenerated());
        copy.setBodyAiGenerated(src.isBodyAiGenerated());
        copy.setTemplateKey(src.getTemplateKey());
        copy.setLocale(src.getLocale());
        copy.setBodyTemplate(src.getBodyTemplate());
        copy.setSenderId(src.getSenderId());
        copy.setCreatedBy(p.userId());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        Campaign saved = campaigns.save(copy);
        audit.record(p, AuditActions.CAMPAIGN_DUPLICATED, "campaign", saved.getId(),
                "Duplicated from \"" + src.getName() + "\"");
        return CampaignDto.from(saved);
    }

    /**
     * Dry run of the send for the composer Audience step: SendGate, then the send-path skips over its
     * sendable members, as RecipientMaterializer applies them. No materialization, no send.
     */
    @Transactional(readOnly = true)
    public PreviewAudienceResponse previewAudience(AuthPrincipal p, UUID id) {
        Campaign c = require(p.orgId(), id);
        if (c.getSegmentId() == null) {
            // no target yet: nothing sendable, nothing excluded
            return new PreviewAudienceResponse(0,
                    new PreviewAudienceResponse.Excluded(0, 0, 0, 0, 0, 0));
        }
        Segment segment = segments.requireSegmentForOrg(p.orgId(), c.getSegmentId());
        SendableAudience audience = sendableAudience(p.orgId(), c, segment);
        SendGateService.GateResult gate = audience.gate();
        Map<UUID, String> skipped = audience.skipped();
        PreviewAudienceResponse.Excluded base = SendGateService.bucket(gate).excluded();
        return new PreviewAudienceResponse(audience.sendableCount(),
                new PreviewAudienceResponse.Excluded(base.noBasis(), base.unsubscribed(),
                        base.marketingSuppressed(), base.deliverabilitySuppressed(), base.noPhone(), base.noEmail(),
                        count(skipped, SendPathGuard.EXPERIMENT_HOLDOUT), count(skipped, SendPathGuard.EVENT_CAP),
                        count(skipped, SendPathGuard.MONTHLY_CAP), count(skipped, SendPathGuard.CONSENT_GATE),
                        base.invalidAddress()));
    }

    private static int count(Map<UUID, String> skipped, String reason) {
        return (int) skipped.values().stream().filter(reason::equals).count();
    }

    /** The send gate's verdict on a segment and the send-path skips over its sendable members. */
    private record SendableAudience(SendGateService.GateResult gate, Map<UUID, String> skipped) {
        int sendableCount() {
            return gate.sendable().size() - skipped.size();
        }
    }

    /** Who the send would mail right now: SendGate, then the send-path skips, as RecipientMaterializer applies them. */
    private SendableAudience sendableAudience(UUID orgId, Campaign c, Segment segment) {
        List<UUID> membershipIds = segments.resolveMembers(orgId, segment).stream()
                .map(Membership::getMembershipId).toList();
        SendGateService.GateResult gate = sendGate.evaluate(orgId, membershipIds);
        return new SendableAudience(gate, sendPathGuard.skipReasons(c, gate.sendable(), Instant.now()));
    }

    /**
     * Refuses a draft the dispatcher could only send as an empty shell, to nobody, or not at all: 400
     * {@code FIELD_INVALID} with every missing field. Writes nothing; the frequency floor is not applied here.
     */
    private void requireReadyToSend(Campaign c) {
        Map<String, String> missing = new LinkedHashMap<>();
        if (c.getSubject() == null || c.getSubject().isBlank()) missing.put("subject", "required");
        if (c.getBodyMd() == null || c.getBodyMd().isBlank()) missing.put("bodyMd", "required");
        if (c.getSegmentId() == null || segments.nameOrNull(c.getOrgId(), c.getSegmentId()) == null) {
            missing.put("segmentId", "required");
        } else if (sendableAudience(c.getOrgId(), c,
                segments.requireSegmentForOrg(c.getOrgId(), c.getSegmentId())).sendableCount() <= 0) {
            missing.put("audience", "empty");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "Campaign is not ready to send", missing);
        }
    }

    /** A past schedule would dispatch at once while the UI says "scheduled"; 2 min absorbs client clock skew. */
    private void requireNotPast(Instant scheduledAt) {
        if (scheduledAt != null && scheduledAt.isBefore(clock.instant().minusSeconds(120))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "Scheduled time has already passed", Map.of("scheduledAt", "must be in the future"));
        }
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
        if (emailProps.getFromAddress() == null || emailProps.getFromAddress().isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                    "Marketing email sender not configured");
        }
        String to = callerEmail(p);   // always the organizer — requestedEmail is advisory only
        Organization org = organizationOrNull(c.getOrgId());
        // The test link names the campaign but unsubscribes nobody; the header keeps the API URL like a real send.
        String testToken = unsubscribeTokens.signTest(c.getOrgId(), c.getId());
        String unsubUrl = emailProps.unsubscribeUrl(testToken);
        // No recipient, so no first name: the token is dropped as for a fan with no name on file.
        CampaignEmailAssembler.Context ctx = assembler.contextFor(c, org);
        CampaignEmailAssembler.Email rendered = assembler.render(ctx, c.getSubject(), c.getPreheader(), c.getBodyMd(),
                null, emailProps.unsubscribePageUrl(testToken), null);
        // Same provider, From, Reply-To and headers as the real batch send, so the test shows what fans will get.
        // No key and no campaign tag: a test is never replayed, and its events are not campaign stats.
        CampaignEmailProvider.BatchResult result = provider.sendBatch(null, List.of(new CampaignEmailProvider.OutgoingEmail(
                ctx.fromHeader(), to,
                "[TEST] " + rendered.subject(), rendered.html(), rendered.text(), unsubUrl, c.aiDisclosure(),
                emailProps.replyTo(org), null)));
        if (result != null && result.rejected(0)) {
            throw new CampaignEmailProvider.CampaignRejected("Email service rejected the test email", 422);
        }
        audit.record(p, "CAMPAIGN_TEST_SENT", "campaign", c.getId(),
                "Test email sent to organizer");
    }

    /**
     * The email as the send would build it, for the dashboard: writes nothing and records no audit row. On a
     * draft the request's subject, preheader, body, template and language stand in for the saved ones (unsaved text);
     * any other status refuses them with 409, since its text can no longer change. {@code named} greets the caller by
     * their own first name; {@code unnamed}, or a caller with no usable first name, gets the no-name greeting.
     */
    @Transactional(readOnly = true)
    public RenderPreviewResponse renderPreview(AuthPrincipal p, UUID id, RenderPreviewRequest req) {
        Campaign c = require(p.orgId(), id);
        if (!"email".equals(c.getChannel())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_STATE,
                    "Only email campaigns have an email preview");
        }
        RenderPreviewRequest r = req == null ? RenderPreviewRequest.SAVED : req;
        if (r.hasOverrides() && !"draft".equals(c.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only a draft can be previewed with unsaved text");
        }
        String subject = r.subject() != null ? r.subject() : c.getSubject();
        String preheader = r.preheader() != null ? r.preheader() : c.getPreheader();
        String bodyMd = r.bodyMd() != null ? r.bodyMd() : c.getBodyMd();
        String templateKey = r.templateKey() != null ? r.templateKey() : c.getTemplateKey();
        String locale = r.locale() != null ? r.locale() : c.getLocale();
        CampaignEmailAssembler.Context ctx = assembler.contextFor(c, organizationOrNull(c.getOrgId()), templateKey, locale);
        String firstName = r.unnamed() ? null
                : MergeTags.firstName(users.findById(p.userId()).map(User::getFirstName).orElse(null));
        // The test-send token: its page says "this was a test" and unsubscribes nobody.
        String unsubUrl = emailProps.unsubscribePageUrl(unsubscribeTokens.signTest(c.getOrgId(), c.getId()));
        CampaignEmailAssembler.Email email = assembler.render(ctx, subject, preheader, bodyMd, firstName, unsubUrl, null);
        String fromAddress = emailProps.getFromAddress() == null || emailProps.getFromAddress().isBlank()
                ? null : emailProps.getFromAddress();
        return new RenderPreviewResponse(email.html(), ctx.fromName(), fromAddress, email.subject(), email.preheader(),
                firstName, CampaignEmailAssembler.notices(ctx, bodyMd),
                CampaignEmailAssembler.links(email.html(), unsubUrl).stream()
                        .map(l -> new RenderPreviewResponse.RenderedLink(l.kind(), l.text(), l.href())).toList(),
                EmailShellCopy.forLocale(locale).lang());
    }

    /** Refuses a campaign that needs a legal identity the org lacks; the org is read only when one is needed. */
    private void requireLegalIdentity(Campaign c) {
        if (!audiencePlanAccess.legalIdentityRequired(c.getOrigin())) return;
        audiencePlanAccess.requireLegalIdentity(c.getOrigin(), organizations.findById(c.getOrgId()).orElse(null));
    }

    /** The campaign's org for the test From, header and footer. Failure-isolated: a hiccup omits the identity. */
    private Organization organizationOrNull(UUID orgId) {
        try {
            return organizations.findById(orgId).orElse(null);
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
     *
     * <p>A draft with no subject, body or resolvable segment, or nobody mailable in it now, is a 400 {@code FIELD_INVALID}
     * after the role, sends-switch, legal-identity and channel refusals; a non-draft replay still gets the 409.
     */
    @Transactional
    public CampaignSendResponse send(UUID campaignId, AuthPrincipal principal,
                                     String idempotencyKey, Instant scheduledAt) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_IDEMPOTENCY_KEY,
                    "Idempotency-Key header is required");
        }
        // Org-scope + existence check (404 leak-safe if not this org's campaign).
        Campaign c = campaigns.findByIdAndOrgId(campaignId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Campaign"));
        // mkt-edge-2: dispatching bulk mail to the whole audience is the most consequential
        // and least reversible thing this controller does; it was the only campaign mutation
        // with neither a role gate nor an audit row. After the 404 so a foreign campaign
        // stays a 404, before the CAS so a refusal never moves the state machine.
        com.imin.iminapi.security.RoleGuard.requireAtLeast(
                principal, com.imin.iminapi.model.UserRole.ADMIN, "send a campaign");
        audiencePlanAccess.requireSendsAllowed(c.getOrigin());
        requireLegalIdentity(c);
        // Fail fast on a channel nothing drains (mkt-core-7). CampaignRepository.claimDue
        // filters WHERE channel='email', so a scheduled SMS campaign was never claimed,
        // never failed and never timed out — it sat 'scheduled' for ever with no signal.
        // Refusing here keeps the state machine honest: the campaign stays a draft.
        if (!"email".equals(c.getChannel())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "SMS campaigns cannot be sent yet — no SMS dispatcher exists");
        }
        // Drafts only: a replay of a campaign already scheduled must keep reaching the compare-and-set's 409.
        if ("draft".equals(c.getStatus())) {
            requireReadyToSend(c);
            requireNotPast(scheduledAt);
        }
        Instant now = clock.instant();
        // Send-now and any time sooner than the undo window are stored a full window ahead; arms keep their own time.
        Instant when = scheduledAt != null && scheduledAt.isAfter(now.plus(SEND_UNDO_WINDOW))
                ? scheduledAt : now.plus(SEND_UNDO_WINDOW);
        // An invitation arm owns its send time; a slump arm is only armed here and waits for Momentum.
        if (AudiencePlanAccess.CAMPAIGN_ORIGIN.equals(c.getOrigin())) {
            var arm = timingArms.onApproval(c, scheduledAt).orElse(null);
            if (arm != null && arm.armed()) {
                audit.record(principal, AuditActions.CAMPAIGN_SENT, "campaign", campaignId,
                        "Campaign armed to send when Momentum detects a sales slump");
                return new CampaignSendResponse(null, true);
            }
            if (arm != null) when = arm.at();
        }
        int updated = campaigns.markScheduledIfDraft(campaignId, principal.orgId(), when);
        if (updated == 0) {
            // Not in draft — duplicate/concurrent send. 409; dispatcher is the sole `sending` path.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Campaign is not in draft");
        }
        // Recorded AFTER the guarded transition won, so a 409 replay does not read as a
        // second dispatch — the trail carries exactly one row per campaign that actually left.
        audit.record(principal, AuditActions.CAMPAIGN_SENT, "campaign", campaignId,
                "Campaign scheduled to send at " + when);
        // Predictor trigger (task §4): a campaign scheduled for an event → re-forecast.
        // AFTER_COMMIT + debounced in ReforecastTriggerService.
        if (eventPublisher != null && c.getEventId() != null) {
            eventPublisher.publishEvent(
                    new com.imin.iminapi.predictor.service.PredictorMarketingEvents.CampaignScheduled(c.getEventId()));
        }
        Organization org = organizationOrNull(principal.orgId());
        String tz = org == null ? null : org.getTimezone();
        return new CampaignSendResponse(when, false,
                Math.max(0, Duration.between(now, when).getSeconds()),
                quietHours.heldUntil(tz, when), quietHours.zoneOf(tz).getId());
    }

    /**
     * Guarded scheduled→draft for a campaign that has not started (attempts 0): the Undo of a send and the
     * "move back to drafts" of a scheduled one. ADMIN only, like send. A campaign the dispatcher has claimed,
     * or that Retry scheduled again, is a 409 INVALID_STATE.
     */
    @Transactional
    public CampaignDto unschedule(AuthPrincipal principal, UUID campaignId) {
        Campaign c = require(principal.orgId(), campaignId);
        com.imin.iminapi.security.RoleGuard.requireAtLeast(
                principal, com.imin.iminapi.model.UserRole.ADMIN, "undo a send");
        // One compare-and-set owns the state check: not scheduled, attempts > 0 or already claimed leaves 0 rows.
        if (campaigns.unscheduleIfNotStarted(campaignId, principal.orgId(), clock.instant()) == 0) {
            throw notUnschedulable();
        }
        // A fired slump arm must not re-fire on a draft, as an edit disarms it.
        if (AudiencePlanAccess.CAMPAIGN_ORIGIN.equals(c.getOrigin())) timingArms.disarm(c);
        audit.record(principal, AuditActions.CAMPAIGN_SEND_UNDONE, "campaign", campaignId,
                "Campaign moved back to drafts before sending");
        return CampaignDto.from(require(principal.orgId(), campaignId));
    }

    private static ApiException notUnschedulable() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                "Only a scheduled campaign that hasn't started can go back to drafts");
    }

    /**
     * One page of a campaign's recipient log, plus honest counts (spec §2.4).
     *
     * <p>{@code status} is the lifecycle filter. Comma-separated for multi-status chips ("Issues" =
     * {@code bounced,failed,complained}); a single value is just a one-element list, so the
     * pre-existing {@code ?status=skipped} call keeps working unchanged. {@code engagement} is refused.
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
        // mkt-edge-1: this hands back every targeted contact's raw address, 200 rows a page over
        // unlimited pages — the same class of disclosure SalesDashboardController.exportAttendees
        // gates on ADMIN. The org-scope 404 above runs FIRST so a foreign campaign stays a 404
        // (no existence leak) rather than becoming a role-shaped 403.
        com.imin.iminapi.security.RoleGuard.requireAtLeast(
                principal, com.imin.iminapi.model.UserRole.ADMIN, "read the campaign recipient log");

        List<String> statuses = parseStatuses(status);
        refuseEngagementFilter(engagement);

        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.ASC, "id"));

        List<com.imin.iminapi.marketing.model.CampaignRecipient> rows;
        long total;
        if (statuses == null) {
            rows = campaignRecipientRepository.findByCampaignId(campaignId, pageable);
            total = campaignRecipientRepository.countByCampaignId(campaignId);
        } else {
            rows = campaignRecipientRepository.findByCampaignIdAndStatusIn(campaignId, statuses, pageable);
            total = campaignRecipientRepository.countByCampaignIdAndStatusIn(campaignId, statuses);
        }

        java.util.Map<UUID, String> names = displayNames(principal.orgId(), rows);
        List<com.imin.iminapi.marketing.dto.RecipientDto> items = rows.stream()
                .map(r -> com.imin.iminapi.marketing.dto.RecipientDto.from(r, displayName(names, r)))
                .toList();

        // One row per opening of the log, not per page: the dashboard pages and polls this
        // endpoint, so auditing every call would drown the trail it exists to leave. Written
        // after the rows are loaded, so a 403/404 never reads as a disclosure that happened.
        if (page == 0) {
            audit.record(principal, AuditActions.CAMPAIGN_RECIPIENTS_VIEWED, "campaign", campaignId,
                    "Campaign recipient log opened (" + total + " row(s) under the active filter)");
        }

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

    /** No per-person opens or clicks: refused, not ignored, so no client takes the whole log for a subset. */
    private static void refuseEngagementFilter(String raw) {
        if (raw == null || raw.isBlank()) return;
        throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                "The recipient log cannot be filtered by opens or clicks",
                java.util.Map.of("engagement", "unsupported"));
    }

    /**
     * Guarded {@code scheduled|sending→canceled}. Any other status is a 409 INVALID_STATE.
     * A sending campaign stops before its next batch; one already at the provider still goes out.
     *
     * <p>Not one transaction, on purpose: the status commits first, then the queue is skipped. The skip waits on the
     * rows of an in-flight batch, and that batch's heartbeat would otherwise wait on this row lock (a deadlock).
     */
    public void cancel(AuthPrincipal principal, UUID campaignId) {
        Campaign c = require(principal.orgId(), campaignId);
        if (CANCELED.equals(c.getStatus())) {
            finishCanceled(principal, c, true);
            return;
        }
        if (!"scheduled".equals(c.getStatus()) && !"sending".equals(c.getStatus())) {
            throw notCancelable();
        }
        // Compare-and-set: a drive that finished or failed it meanwhile wins, and the organizer gets the same 409.
        if (campaigns.cancelIfActive(c.getId(), principal.orgId(), Instant.now()) == 0) {
            throw notCancelable();
        }
        finishCanceled(principal, c, false);
    }

    /**
     * Skips what the canceled campaign still has queued and audits the cancel once. A repeat cancel resumes one whose
     * skip failed after the status committed; with nothing queued it is the usual 409.
     */
    private void finishCanceled(AuthPrincipal principal, Campaign c, boolean repeat) {
        int notSent = campaignRecipientRepository.skipPendingOfCanceled(
                c.getId(), com.imin.iminapi.marketing.model.CampaignRecipient.SKIP_CAMPAIGN_CANCELED, Instant.now());
        if (repeat && notSent == 0) throw notCancelable();
        if (repeat && auditLogs.existsByOrgIdAndActionAndTargetTypeAndTargetId(
                principal.orgId(), "CAMPAIGN_CANCELED", "campaign", c.getId())) {
            return;
        }
        long sent = campaignRecipientRepository.countByCampaignIdAndStatusIn(c.getId(), LEFT_THE_QUEUE);
        audit.record(principal, "CAMPAIGN_CANCELED", "campaign", c.getId(),
                "Campaign canceled: " + sent + " sent, " + notSent + " not sent");
    }

    private static ApiException notCancelable() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                "Only scheduled or sending campaigns can be canceled");
    }

    /**
     * Guarded retry of a {@code failed} campaign (spec §2.4), the only way out of {@code failed} since the dispatcher
     * never claims one: back to {@code scheduled} at now while attempts &lt; 3, else (or any other status) 409 INVALID_STATE.
     * ADMIN or OWNER only (403 for a MEMBER), like send. Rows still queued under a batch key are replayed with that key.
     */
    @Transactional
    public Campaign retry(AuthPrincipal principal, UUID campaignId) {
        Campaign c = require(principal.orgId(), campaignId);
        // Retry mails every requeued recipient, so it needs the role Send needs; after the 404, before any state change.
        com.imin.iminapi.security.RoleGuard.requireAtLeast(
                principal, com.imin.iminapi.model.UserRole.ADMIN, "retry a campaign");
        if (!"failed".equals(c.getStatus()) || c.getAttempts() >= MAX_ATTEMPTS) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Campaign is not retryable");
        }
        audiencePlanAccess.requireSendsAllowed(c.getOrigin());
        requireLegalIdentity(c);
        // Compare-and-set: a concurrent retry that already scheduled it wins, and this one gets the same 409.
        if (campaigns.retryIfFailed(c.getId(), principal.orgId(), Instant.now()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Campaign is not retryable");
        }
        // Put the dead rows back in the queue (mkt-core-2). Recipients that burned their
        // attempt budget are 'failed', and the dispatcher only claims 'pending' — without
        // this the retry re-claimed a campaign with nothing left to send and failed again.
        // Rows that already left (sent/delivered/…) are untouched, so nobody is re-emailed.
        campaignRecipientRepository.requeueFailed(campaignId);
        audit.record(principal, AuditActions.CAMPAIGN_RETRIED, "campaign", c.getId(), "Campaign retry queued");
        return require(principal.orgId(), campaignId);
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
        // Locked re-check: a send or slump arm that scheduled the draft meanwhile wins, and this answers 409.
        c = lockFresh(principal.orgId(), campaignId);
        if (!"draft".equals(c.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only draft campaigns can be deleted");
        }
        campaignRecipientRepository.deleteByCampaignId(campaignId);
        campaigns.delete(c);
        audit.record(principal, AuditActions.CAMPAIGN_DELETED, "campaign", campaignId,
                "Draft campaign deleted");
    }

    /** Campaign detail + aggregate stats block (spec §2.4/§3), with its segment's name and event's zone. Org-scoped. */
    @Transactional(readOnly = true)
    public com.imin.iminapi.marketing.dto.CampaignDetailDto detailWithStats(AuthPrincipal p, UUID id) {
        Campaign c = require(p.orgId(), id);
        String segmentName = c.getSegmentId() == null ? null : segments.nameOrNull(p.orgId(), c.getSegmentId());
        Event event = linkedEvent(c);
        // Trigger comes from the suggestion this campaign was approved from; null for manual ones.
        String triggerType = c.getMomentumSuggestionId() == null ? null
                : momentumSuggestions.findById(c.getMomentumSuggestionId())
                        .filter(sg -> p.orgId().equals(sg.getOrgId()))
                        .map(com.imin.iminapi.marketing.model.MomentumSuggestion::getTriggerType)
                        .orElse(null);
        RecipientCounts counts = campaignRecipientRepository.chipCounts(id);
        // Live from the rows (mid-send diverts included), not the snapshot frozen at materialization.
        Map<String, Long> skipReasons = counts.total() == 0 ? null : skipReasonCounts(id);
        String sendHold = waitingToSend(c) ? sendHolds(p.orgId()).of(c) : null;
        return com.imin.iminapi.marketing.dto.CampaignDetailDto.from(c, statsOf(p.orgId(), id, counts), segmentName,
                event == null ? null : event.getTimezone(), triggerType,
                CampaignFailureReason.of(c.getLastError()), event == null ? null : event.getName(),
                attributedRevenue(p.orgId(), c), skipReasons, sendHold);
    }

    /**
     * Aggregate send/engagement counts for one of the org's campaigns (spec §3), from one pass over its rows.
     * {@code sent} is every row that left the queue; {@code delivered} was accepted by the recipient's mail provider, later unsubscribes and
     * complaints included; {@code skipped}/{@code failed}/{@code queued} are live row counts;
     * {@code opened}/{@code clicked} count rows the webhook stamped (unsubscribe-link clicks and objected members are
     * not; rows stamped before 21 October 2026 may include both); {@code attributedPurchases} counts
     * checkout-start sessions with the campaign's tag, {@code attributedOrders} the live orders behind revMinor.
     */
    @Transactional(readOnly = true)
    public com.imin.iminapi.marketing.dto.CampaignStatsDto stats(UUID orgId, UUID campaignId) {
        return statsOf(orgId, campaignId, campaignRecipientRepository.chipCounts(campaignId));
    }

    private com.imin.iminapi.marketing.dto.CampaignStatsDto statsOf(UUID orgId, UUID campaignId, RecipientCounts c) {
        long attributed = attribution.attributedPurchaseCount(campaignId);
        long attributedOrders = attribution.attributedOrderCount(orgId, campaignId);
        return new com.imin.iminapi.marketing.dto.CampaignStatsDto(
                c.sent(), c.delivered(), c.opened(), c.clicked(), c.bounced(), c.unsubscribed(), c.complained(),
                attributed, attributedOrders, c.queued(), c.failed(), c.skipped());
    }

    private Map<String, Long> skipReasonCounts(UUID campaignId) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : campaignRecipientRepository.skipReasonCounts(campaignId)) {
            // A skipped row always carries a reason; the key guards a null from an older writer.
            out.merge(row[0] == null ? "unknown" : (String) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
        return out;
    }

    // ---- helpers ----

    Campaign require(UUID orgId, UUID id) {
        return campaigns.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                        "Campaign not found"));
    }

    /** Row-locked load; refreshed because the locked query returns the stale copy this transaction already loaded. */
    private Campaign lockFresh(UUID orgId, UUID id) {
        Campaign c = campaigns.findByIdAndOrgIdForUpdate(id, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Campaign not found"));
        entityManager.refresh(c);
        return c;
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
