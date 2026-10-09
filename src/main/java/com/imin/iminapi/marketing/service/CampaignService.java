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
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.render.CampaignEmailRenderer;
import com.imin.iminapi.marketing.render.OrganizerIdentity;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Marketing campaign CRUD + duplicate + (Task 5) preview-audience + test-send.
 * Org comes ONLY from the AuthPrincipal — never a path/body var (SPINE INVARIANT).
 */
@Service
public class CampaignService {

    static final Set<String> CHANNELS = Set.of("email", "sms");
    private static final int MAX_NAME = 120;
    /** Recipient statuses of a row that left the queue: what {@code stats.sent} and the cancel audit count. */
    private static final String CANCELED = "canceled";
    private static final List<String> LEFT_THE_QUEUE = List.of(
            "sent", "delivered", "opened", "clicked", "bounced", "complained", "unsubscribed");

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
    // Test-send now renders through the SAME branded shell the real batch send uses
    // (CampaignEmailRenderer + resolved template), so a preview matches the delivered mail.
    private final CampaignEmailRenderer renderer;
    private final CampaignTemplateService templateService;
    private final OrganizationRepository organizations;
    private final EventRepository events;
    private final MarketingEmailProperties emailProps;
    private final AudiencePlanAccess audiencePlanAccess;
    private final CampaignAiSuggestions aiSuggestions;
    private final SendPathGuard sendPathGuard;
    private final com.imin.iminapi.audienceplan.service.TimingArmScheduler timingArms;
    private final com.imin.iminapi.repository.AuditLogRepository auditLogs;
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
                           CampaignEmailRenderer renderer, CampaignTemplateService templateService,
                           OrganizationRepository organizations, EventRepository events,
                           MarketingEmailProperties emailProps,
                           AudiencePlanAccess audiencePlanAccess,
                           CampaignAiSuggestions aiSuggestions,
                           SendPathGuard sendPathGuard,
                           com.imin.iminapi.audienceplan.service.TimingArmScheduler timingArms,
                           com.imin.iminapi.repository.AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
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
        this.renderer = renderer;
        this.templateService = templateService;
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
        List<UUID> canceledIds = rows.stream().filter(c -> CANCELED.equals(c.getStatus())).map(Campaign::getId).toList();
        Set<UUID> canceledAfterSending = canceledIds.isEmpty() ? Set.of()
                : Set.copyOf(campaignRecipientRepository.findCampaignIdsWithStatusIn(canceledIds, LEFT_THE_QUEUE));
        java.util.function.Predicate<Campaign> sent = c -> hasSent(c) || canceledAfterSending.contains(c.getId());
        List<UUID> sentIds = rows.stream().filter(sent).map(Campaign::getId).toList();
        var revByCampaign = attribution.attributedRevenueMinorByCampaign(p.orgId(), sentIds);
        // Segment names and linked events for the page in one query each, org-scoped like detail.
        var segmentNames = segments.namesByIds(p.orgId(), idsOf(rows, Campaign::getSegmentId));
        var linkedEvents = activeEventsByIds(p.orgId(), idsOf(rows, Campaign::getEventId));
        return rows.stream()
                .map(c -> {
                    Event e = c.getEventId() == null ? null : linkedEvents.get(c.getEventId());
                    return CampaignSummary.from(c,
                            sent.test(c) ? revByCampaign.getOrDefault(c.getId(), 0L) : null,
                            c.getSegmentId() == null ? null : segmentNames.get(c.getSegmentId()),
                            e == null ? null : e.getName(),
                            e == null ? null : e.getTimezone());
                })
                .toList();
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
        boolean sent = hasSent(c) || (CANCELED.equals(c.getStatus())
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
     * a half-sent campaign that is already driving real orders. A campaign canceled mid-send is
     * checked against its recipient rows by the callers (one query per page in the list).
     */
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
        if (AudiencePlanAccess.CAMPAIGN_ORIGIN.equals(c.getOrigin())) timingArms.disarm(c);
        if (req.name() != null) c.setName(requireName(req.name()));
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

    /** An invitation arm keeps its segment and event, so its experiment link and holdout skip stay intact. */
    private static void requirePlanTargetKept(Campaign c, PatchCampaignRequest req) {
        if (!AudiencePlanAccess.CAMPAIGN_ORIGIN.equals(c.getOrigin())) return;
        boolean segmentChanged = req.segmentId() != null && !Objects.equals(req.segmentId().value(), c.getSegmentId());
        boolean eventChanged = req.eventId() != null && !Objects.equals(req.eventId().value(), c.getEventId());
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
        List<UUID> membershipIds = segments.resolveMembers(p.orgId(), segment).stream()
                .map(Membership::getMembershipId).toList();
        SendGateService.GateResult gate = sendGate.evaluate(p.orgId(), membershipIds);
        PreviewAudienceResponse.Excluded base = SendGateService.bucket(gate).excluded();
        Map<UUID, String> skipped = sendPathGuard.skipReasons(c, gate.sendable(), Instant.now());
        return new PreviewAudienceResponse(gate.sendable().size() - skipped.size(),
                new PreviewAudienceResponse.Excluded(base.noBasis(), base.unsubscribed(),
                        base.marketingSuppressed(), base.deliverabilitySuppressed(), base.noPhone(), base.noEmail(),
                        count(skipped, SendPathGuard.EXPERIMENT_HOLDOUT), count(skipped, SendPathGuard.EVENT_CAP),
                        count(skipped, SendPathGuard.MONTHLY_CAP), count(skipped, SendPathGuard.CONSENT_GATE)));
    }

    private static int count(Map<UUID, String> skipped, String reason) {
        return (int) skipped.values().stream().filter(reason::equals).count();
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
        String unsubUrl = emailProps.unsubscribeUrl("preview");
        CampaignEmailRenderer.Rendered rendered = renderForTest(c, org, unsubUrl);
        // Same provider, From and headers as the real batch send, so the test shows what fans will get.
        provider.sendBatch(List.of(new CampaignEmailProvider.OutgoingEmail(
                emailProps.fromHeader(org == null ? null : org.displayName()), to,
                "[TEST] " + c.getSubject(), rendered.html(), rendered.text(), unsubUrl, c.aiDisclosure())));
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
    private CampaignEmailRenderer.Rendered renderForTest(Campaign c, Organization org, String unsubUrl) {
        // resolve() coalesces null/blank/unknown template_key to the classic builtin, so a pre-V66
        // or NULL row still renders inside the branded shell rather than as bare text.
        ResolvedTemplate template = templateService.resolve(c.getOrgId(), c.getTemplateKey());
        String brandName = org == null ? null : org.displayName();
        Event event = linkedEvent(c);
        String posterUrl = event == null ? null : event.getPosterUrl();
        String ticketsUrl = event == null ? null : emailProps.getBuyerSiteBaseUrl() + "/e/" + event.getId();
        // A test send has no recipient, so resolve merge tags to a preview sample: the
        // neutral first-name fallback and the real event URL — so the tester sees resolved
        // text, not raw {{firstName}}/{{eventUrl}} tokens.
        String eventUrl = ticketsUrl != null ? ticketsUrl : emailProps.getBuyerSiteBaseUrl();
        String subject = com.imin.iminapi.marketing.render.MergeTags.apply(
                c.getSubject(), com.imin.iminapi.marketing.render.MergeTags.FIRST_NAME_FALLBACK, eventUrl);
        String bodyMd = com.imin.iminapi.marketing.render.MergeTags.apply(
                c.getBodyMd(), com.imin.iminapi.marketing.render.MergeTags.FIRST_NAME_FALLBACK, eventUrl);
        String preheader = com.imin.iminapi.marketing.render.MergeTags.apply(
                c.getPreheader(), com.imin.iminapi.marketing.render.MergeTags.FIRST_NAME_FALLBACK, eventUrl);
        return renderer.render(
                subject, preheader, bodyMd,
                c.getId().toString(), "email", unsubUrl,
                template, brandName, posterUrl, ticketsUrl, c.aiDisclosure(), OrganizerIdentity.of(org));
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
        Instant when = scheduledAt != null ? scheduledAt : Instant.now();
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
        return new CampaignSendResponse(when, false);
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
        // mkt-edge-1: this hands back every targeted contact's raw address, 200 rows a page over
        // unlimited pages — the same class of disclosure SalesDashboardController.exportAttendees
        // gates on ADMIN. The org-scope 404 above runs FIRST so a foreign campaign stays a 404
        // (no existence leak) rather than becoming a role-shaped 403.
        com.imin.iminapi.security.RoleGuard.requireAtLeast(
                principal, com.imin.iminapi.model.UserRole.ADMIN, "read the campaign recipient log");

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
        audiencePlanAccess.requireSendsAllowed(c.getOrigin());
        requireLegalIdentity(c);
        // Compare-and-set: a claim that took the failed row meanwhile wins, and the organizer gets the same 409.
        if (campaigns.retryIfFailed(c.getId(), principal.orgId(), Instant.now()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Campaign is not retryable");
        }
        // Put the dead rows back in the queue (mkt-core-2). Recipients that burned their
        // attempt budget are 'failed', and the dispatcher only claims 'pending' — without
        // this the retry re-claimed a campaign with nothing left to send and failed again.
        // Rows that already left (sent/delivered/…) are untouched, so nobody is re-emailed.
        campaignRecipientRepository.requeueFailed(campaignId);
        audit.record(principal, "CAMPAIGN_RETRIED", "campaign", c.getId(), "Campaign retry queued");
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
        return com.imin.iminapi.marketing.dto.CampaignDetailDto.from(c, stats(id), segmentName,
                event == null ? null : event.getTimezone());
    }

    /**
     * Aggregate send/engagement counts for a campaign (spec §3). {@code sent} is every row that
     * left the queue (any lifecycle status past pending/failed/skipped); {@code opened}/{@code clicked}
     * read the webhook-projected timestamps; {@code attributedPurchases} is the utm_campaign funnel count.
     */
    @Transactional(readOnly = true)
    public com.imin.iminapi.marketing.dto.CampaignStatsDto stats(UUID campaignId) {
        long sent = campaignRecipientRepository.countByCampaignIdAndStatusIn(campaignId, LEFT_THE_QUEUE);
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
