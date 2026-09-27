package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.TimingArm;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsRequest;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsRequest.SegmentInvitation;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsResponse.Arm;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsResponse.Holdout;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsResponse.Invitation;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder;
import com.imin.iminapi.audienceplan.engine.ResponseModel;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.model.AudiencePlan;
import com.imin.iminapi.audienceplan.model.AudiencePlanSegment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.audienceplan.repository.AudiencePlanRepository;
import com.imin.iminapi.audienceplan.repository.AudiencePlanSegmentRepository;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns chosen segments of an event's current plan into an experiment: holdout + timing arms, assignments first,
 * then one hidden static segment and one draft campaign per arm. Never schedules or sends anything.
 */
@Service
public class InvitationService {

    static final int HOLDOUT_PCT_MIN = 10;
    static final int HOLDOUT_PCT_MAX = 20;
    static final int MAX_CAMPAIGN_NAME = 120;
    static final int MAX_SEGMENT_NAME = 128;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AudiencePlanAccess access;
    private final EventRepository events;
    private final AudiencePlanRepository plans;
    private final AudiencePlanSegmentRepository planSegments;
    private final AudienceExperimentRepository experiments;
    private final AudienceAssignmentRepository assignments;
    private final PlanService planService;
    private final CandidateLoader candidates;
    private final CandidateBuilder builder;
    private final ExperimentService experimentService;
    private final SegmentRepository segments;
    private final CampaignRepository campaigns;
    private final AudiencePlanLogic logic;
    private final AuditLogger audit;
    private final Clock clock;

    public InvitationService(AudiencePlanAccess access, EventRepository events, AudiencePlanRepository plans,
                             AudiencePlanSegmentRepository planSegments, AudienceExperimentRepository experiments,
                             AudienceAssignmentRepository assignments, PlanService planService,
                             CandidateLoader candidates, ExperimentService experimentService,
                             SegmentRepository segments, CampaignRepository campaigns, AudiencePlanLogic logic,
                             ResponseModel model, AuditLogger audit, Clock clock) {
        this.access = access;
        this.events = events;
        this.plans = plans;
        this.planSegments = planSegments;
        this.experiments = experiments;
        this.assignments = assignments;
        this.planService = planService;
        this.candidates = candidates;
        this.builder = new CandidateBuilder(logic, model);
        this.experimentService = experimentService;
        this.segments = segments;
        this.campaigns = campaigns;
        this.logic = logic;
        this.audit = audit;
        this.clock = clock;
    }

    /** {@code requestedPct} is null when the body left it out; {@code holdoutPct} is then the logic default. */
    private record Wanted(String field, AudiencePlanSegment planSegment, List<String> arms, int holdoutPct,
                          Integer requestedPct) {}

    @Transactional
    public AudiencePlanInvitationsResponse invite(AuthPrincipal principal, UUID eventId,
                                                  AudiencePlanInvitationsRequest request) {
        UUID orgId = principal.orgId();
        access.requireEnabled(orgId);
        Event event = events.findActive(eventId).filter(e -> orgId.equals(e.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("Audience plan"));
        Instant now = clock.instant();
        if (event.getStartsAt() == null || !event.getStartsAt().isAfter(now)) {
            throw ApiException.invalidState("The event has already started");
        }
        // The per-event lock first plans use; held to commit, so invitations of one event run one at a time
        // whichever plan generation each one reads.
        planService.lockFirstPlan(eventId);
        AudiencePlan plan = plans.findFirstByOrgIdAndEventIdAndSupersededByIsNullOrderByCreatedAtDesc(orgId, eventId)
                .orElseThrow(() -> ApiException.notFound("Audience plan"));
        List<Wanted> wanted = validate(request, planSegments.findByPlanIdOrderByPositionAsc(plan.getId()));
        boolean recreate = request != null && Boolean.TRUE.equals(request.recreateMissingDrafts());

        // Keyed per event × class × fit, so a plan refresh cannot invite the same people twice. Read before the
        // D-3 check: a stored d3 arm stays readable after a refreshed plan lost its D-3 date.
        List<List<AudienceExperiment>> stored = new ArrayList<>();
        for (Wanted w : wanted) {
            AudiencePlanSegment ps = w.planSegment();
            List<AudienceExperiment> existing = experiments.findInvited(orgId, eventId, ps.getClassKey(), ps.getGenreFit());
            if (existing.isEmpty()) requireArmsAvailable(w, plan);
            stored.add(existing);
        }

        List<Invitation> out = new ArrayList<>();
        CandidateBuilder.Result current = null;
        for (int i = 0; i < wanted.size(); i++) {
            Wanted w = wanted.get(i);
            AudiencePlanSegment ps = w.planSegment();
            List<AudienceExperiment> existing = stored.get(i);
            if (!existing.isEmpty()) {
                out.add(stored(principal, event, ps, w, existing, recreate, now));
                continue;
            }
            // ConsentGate and the send exclusions are re-read now, not taken from the stored plan.
            if (current == null) {
                current = builder.build(candidates.input(orgId, event, plan.getTargetTickets(),
                        plan.getTicketsPerOrder()));
            }
            List<UUID> members = members(current, ps);
            if (members.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                        "No one in this segment can be emailed now; refresh the plan",
                        Map.of("segment", ps.getClassKey() + "/" + ps.getGenreFit()));
            }
            out.add(create(principal, event, plan, ps, w, members, now));
        }
        return new AudiencePlanInvitationsResponse(eventId, access.sendsEnabled(), List.copyOf(out));
    }

    // ── create ─────────────────────────────────────────────────────────────

    private Invitation create(AuthPrincipal principal, Event event, AudiencePlan plan, AudiencePlanSegment ps,
                              Wanted w, List<UUID> members, Instant now) {
        long seed = experimentService.newSeed();
        ExperimentService.Split split = ExperimentService.split(members, seed, w.holdoutPct(),
                logic.logic().experiments().holdoutMinMailable(), w.arms());
        // Assignments are stored before any campaign exists, so the holdout skip already covers these people.
        ExperimentService.Recorded recorded = experimentService.record(principal.orgId(), event.getId(), plan.getId(),
                ps.getId(), seed, w.holdoutPct(), split);

        List<Arm> arms = new ArrayList<>();
        for (Map.Entry<String, AudienceExperiment> e : recorded.arms().entrySet()) {
            String arm = e.getKey();
            List<UUID> ids = split.arms().get(arm);
            arms.add(attachDraft(principal, event, ps, e.getValue(), ids, now));
        }
        AudienceExperiment h = recorded.holdout();
        return new Invitation(ps.getClassKey(), ps.getGenreFit(), plan.getId(), ps.getId(), members.size(),
                h == null ? null : new Holdout(h.getId(), h.getMembers()), List.copyOf(arms), true);
    }

    /** One hidden static segment of exactly {@code ids} and a draft campaign on it, linked to the arm experiment. */
    private Arm attachDraft(AuthPrincipal principal, Event event, AudiencePlanSegment ps, AudienceExperiment experiment,
                            List<UUID> ids, Instant now) {
        String label = label(ps, experiment.getArm(), event.getName());
        Segment segment = armSegment(principal, label, ids);
        Campaign campaign = draft(principal, event, segment, label, now);
        experiment.setCampaignId(campaign.getId());
        experiments.save(experiment);
        return new Arm(experiment.getArm(), experiment.getId(), ids.size(), segment.getId(), campaign.getId(), false);
    }

    private Segment armSegment(AuthPrincipal principal, String label, List<UUID> ids) {
        Segment s = new Segment();
        s.setOrgId(principal.orgId());
        s.setName(truncate(label, MAX_SEGMENT_NAME));
        s.setKind("static");
        s.setOrigin(Segment.ORIGIN_AUDIENCE_PLAN);
        s.setSnapshotIds(json(ids.stream().map(UUID::toString).toList()));
        Segment saved = segments.save(s);
        audit.record(principal, AuditActions.SEGMENT_CREATED, "segment", saved.getId(),
                "Audience plan arm segment: " + ids.size() + " members");
        return saved;
    }

    private Campaign draft(AuthPrincipal principal, Event event, Segment segment, String label, Instant now) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(principal.orgId());
        c.setChannel("email");
        c.setName(truncate(label, MAX_CAMPAIGN_NAME));
        c.setStatus("draft");
        c.setOrigin(AudiencePlanAccess.CAMPAIGN_ORIGIN);
        c.setSegmentId(segment.getId());
        c.setEventId(event.getId());
        c.setCreatedBy(principal.userId());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        Campaign saved = campaigns.save(c);
        audit.record(principal, AuditActions.CAMPAIGN_CREATED, "campaign", saved.getId(),
                "Audience plan draft created: " + saved.getName());
        return saved;
    }

    private static List<UUID> members(CandidateBuilder.Result current, AudiencePlanSegment ps) {
        for (CandidateBuilder.Segment s : current.segments()) {
            if (s.classKey().equals(ps.getClassKey()) && key(s.fit()).equals(ps.getGenreFit())) {
                return s.membershipIds();
            }
        }
        return List.of();
    }

    // ── stored ─────────────────────────────────────────────────────────────

    /**
     * The first invitation of this class × fit (a later generation's rows could only come from a pre-lock race).
     * The assignment is permanent: other arms or another explicit holdout percentage are a 409, and a deleted draft
     * is rebuilt only on request.
     */
    private Invitation stored(AuthPrincipal principal, Event event, AudiencePlanSegment requested, Wanted w,
                              List<AudienceExperiment> existing, boolean recreate, Instant now) {
        UUID planSegmentId = existing.get(0).getPlanSegmentId();
        UUID planId = existing.get(0).getPlanId();
        AudienceExperiment holdout = null;
        Map<String, AudienceExperiment> byArm = new LinkedHashMap<>();
        for (AudienceExperiment e : existing) {
            if (!planSegmentId.equals(e.getPlanSegmentId())) continue;
            if (AudienceExperiment.ARM_HOLDOUT.equals(e.getArm())) holdout = e;
            else byArm.put(e.getArm(), e);
        }
        List<AudienceExperiment> ordered = new ArrayList<>();
        for (String arm : canonicalArms()) {
            AudienceExperiment e = byArm.remove(arm);
            if (e != null) ordered.add(e);
        }
        ordered.addAll(byArm.values());
        List<String> storedArms = ordered.stream().map(AudienceExperiment::getArm).toList();
        Integer storedPct = ordered.isEmpty() ? null : ordered.get(0).getHoldoutPct();
        Map<String, String> conflicts = new LinkedHashMap<>();
        if (!new HashSet<>(storedArms).equals(new HashSet<>(w.arms()))) {
            conflicts.put(w.field() + ".arms", String.join(",", storedArms));
        }
        // An omitted holdoutPct accepts the stored one; rows from before it was recorded cannot conflict.
        if (w.requestedPct() != null && storedPct != null && !storedPct.equals(w.requestedPct())) {
            conflicts.put(w.field() + ".holdoutPct", String.valueOf(storedPct));
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.AUDIENCE_PLAN_ALREADY_INVITED,
                    "This segment was already invited with other settings", Map.copyOf(conflicts));
        }

        int members = holdout == null ? 0 : holdout.getMembers();
        List<Arm> arms = new ArrayList<>();
        for (AudienceExperiment e : ordered) {
            members += e.getMembers();
            Campaign c = e.getCampaignId() == null ? null
                    : campaigns.findByIdAndOrgId(e.getCampaignId(), principal.orgId()).orElse(null);
            if (c != null) {
                arms.add(new Arm(e.getArm(), e.getId(), e.getMembers(), c.getSegmentId(), c.getId(), false));
            } else if (recreate) {
                arms.add(attachDraft(principal, event, requested, e, assignments.findMembershipIds(e.getId()), now));
            } else {
                arms.add(new Arm(e.getArm(), e.getId(), e.getMembers(), null, null, true));
            }
        }
        return new Invitation(requested.getClassKey(), requested.getGenreFit(), planId, planSegmentId, members,
                holdout == null ? null : new Holdout(holdout.getId(), holdout.getMembers()), List.copyOf(arms), false);
    }

    // ── validation ─────────────────────────────────────────────────────────

    private List<Wanted> validate(AudiencePlanInvitationsRequest request, List<AudiencePlanSegment> shown) {
        if (request == null || request.segments() == null || request.segments().isEmpty()) {
            throw invalid("segments", "at least one segment is required");
        }
        int defaultPct = logic.logic().experiments().holdoutPct();
        Set<String> seen = new HashSet<>();
        List<Wanted> out = new ArrayList<>();
        for (int i = 0; i < request.segments().size(); i++) {
            String field = "segments[" + i + "]";
            SegmentInvitation s = request.segments().get(i);
            if (s == null) throw invalid(field, "must not be null");
            AudiencePlanSegment ps = shown.stream()
                    .filter(p -> p.getClassKey().equals(s.classKey()) && p.getGenreFit().equals(s.genreFit()))
                    .findFirst()
                    .orElseThrow(() -> invalid(field, "not a segment of the current plan: "
                            + s.classKey() + "/" + s.genreFit()));
            if (!seen.add(ps.getClassKey() + "/" + ps.getGenreFit())) throw invalid(field, "segment listed twice");
            Integer pct = s.holdoutPct();
            if (pct != null && (pct < HOLDOUT_PCT_MIN || pct > HOLDOUT_PCT_MAX)) {
                throw invalid(field + ".holdoutPct", "must be between " + HOLDOUT_PCT_MIN + " and " + HOLDOUT_PCT_MAX);
            }
            out.add(new Wanted(field, ps, arms(field + ".arms", s.arms()), pct == null ? defaultPct : pct, pct));
        }
        return out;
    }

    /** Known, distinct arms in canonical order. */
    private static List<String> arms(String field, List<String> raw) {
        if (raw == null || raw.isEmpty()) throw invalid(field, "at least one arm is required");
        List<String> known = canonicalArms();
        Set<String> picked = new HashSet<>();
        for (String a : raw) {
            if (a == null || !known.contains(a)) throw invalid(field, "unknown arm: " + a);
            if (!picked.add(a)) throw invalid(field, "arm listed twice: " + a);
        }
        return known.stream().filter(picked::contains).toList();
    }

    /** A new invitation gets d3 only while the plan still has a D-3 date. */
    private static void requireArmsAvailable(Wanted w, AudiencePlan plan) {
        if (w.arms().contains(key(TimingArm.D3)) && plan.getD3Date() == null) {
            throw invalid(w.field() + ".arms", "d3 is not available: the event is too close");
        }
    }

    private static List<String> canonicalArms() {
        List<String> out = new ArrayList<>();
        for (TimingArm a : TimingArm.values()) out.add(key(a));
        return out;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed", Map.of(field, message));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Data, not UI copy: the class, fit and arm keys plus the event name. */
    static String label(AudiencePlanSegment ps, String arm, String eventName) {
        String base = "Audience plan · " + ps.getClassKey() + "/" + ps.getGenreFit() + " · " + arm;
        return eventName == null || eventName.isBlank() ? base : base + " · " + eventName.trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write arm segment ids", e);
        }
    }
}
