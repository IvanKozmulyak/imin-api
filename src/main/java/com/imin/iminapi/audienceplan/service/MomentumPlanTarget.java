package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder;
import com.imin.iminapi.audienceplan.model.AudiencePlan;
import com.imin.iminapi.audienceplan.model.AudiencePlanSegment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudiencePlanRepository;
import com.imin.iminapi.audienceplan.repository.AudiencePlanSegmentRepository;
import com.imin.iminapi.model.Event;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Picks who a Momentum draft should email from the event's current audience plan: the shown segment with the
 * highest expected mid, re-derived now, without the event's holdout members. Empty means "keep the default target".
 */
@Service
public class MomentumPlanTarget {

    static final int MAX_NAME = 128;
    /** Marks a static segment as a plan-derived Momentum snapshot, so SendPathGuard applies ConsentGate at send. */
    public static final String SNAPSHOT_KEY = "MOMENTUM_PLAN";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The chosen plan segment and its members as of now, holdouts already removed. */
    public record Target(String classKey, String genreFit, List<UUID> membershipIds) {}

    private final AudiencePlanAccess access;
    private final AudiencePlanRepository plans;
    private final AudiencePlanSegmentRepository planSegments;
    private final CandidateLoader candidates;
    private final AudienceAssignmentRepository assignments;
    private final SegmentRepository segments;
    private final AudiencePlanLogic logic;

    public MomentumPlanTarget(AudiencePlanAccess access, AudiencePlanRepository plans,
                              AudiencePlanSegmentRepository planSegments, CandidateLoader candidates,
                              AudienceAssignmentRepository assignments, SegmentRepository segments,
                              AudiencePlanLogic logic) {
        this.access = access;
        this.plans = plans;
        this.planSegments = planSegments;
        this.candidates = candidates;
        this.assignments = assignments;
        this.segments = segments;
        this.logic = logic;
    }

    /** Reads only; nothing is written until {@link #snapshot}. */
    public Optional<Target> best(Event event) {
        UUID orgId = event.getOrgId();
        if (!access.isEnabled(orgId)) return Optional.empty();
        Optional<AudiencePlan> plan =
                plans.findFirstByOrgIdAndEventIdAndSupersededByIsNullOrderByCreatedAtDesc(orgId, event.getId());
        if (plan.isEmpty()) return Optional.empty();
        int min = logic.logic().minSegmentToShow();

        Optional<AudiencePlanSegment> best = planSegments.findByPlanIdOrderByPositionAsc(plan.get().getId()).stream()
                .filter(s -> s.getMailable() >= min)
                .min(Comparator.comparingInt(AudiencePlanSegment::getExpectedMid).reversed()
                        .thenComparingInt(AudiencePlanSegment::getPosition));
        if (best.isEmpty()) return Optional.empty();
        String classKey = best.get().getClassKey();
        String fit = best.get().getGenreFit();

        // The stored plan holds counts only, so the members are re-derived with the plan's own assumptions.
        CandidateBuilder.Result now = candidates.build(orgId, event, plan.get().getTargetTickets(),
                plan.get().getTicketsPerOrder());
        Optional<CandidateBuilder.Segment> current = now.segments().stream()
                .filter(s -> s.classKey().equals(classKey) && s.fit().name().toLowerCase(Locale.ROOT).equals(fit))
                .findFirst();
        if (current.isEmpty()) return Optional.empty();

        List<UUID> members = current.get().membershipIds();
        Set<UUID> heldOut = new HashSet<>();
        for (int from = 0; from < members.size(); from += SendPathGuard.MAX_IDS_PER_QUERY) {
            List<UUID> chunk = members.subList(from, Math.min(from + SendPathGuard.MAX_IDS_PER_QUERY, members.size()));
            heldOut.addAll(assignments.findHeldOut(orgId, event.getId(), chunk));
        }
        List<UUID> kept = new ArrayList<>(members.size());
        for (UUID id : members) {
            if (!heldOut.contains(id)) kept.add(id);
        }
        if (kept.size() < min) return Optional.empty();
        return Optional.of(new Target(classKey, fit, List.copyOf(kept)));
    }

    /** Freezes the target into a static segment the draft campaign will send to; returns its id. */
    public UUID snapshot(UUID orgId, String eventName, Target target) {
        List<String> ids = target.membershipIds().stream().map(UUID::toString).sorted().toList();
        Segment s = new Segment();
        s.setOrgId(orgId);
        s.setName(name(eventName, target));
        s.setKind("static");
        s.setPrebuiltKey(SNAPSHOT_KEY);
        try {
            s.setSnapshotIds(JSON.writeValueAsString(ids));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Momentum target", e);
        }
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return segments.save(s).getId();
    }

    /** Removes a snapshot whose suggestion was never stored. */
    public void discard(UUID orgId, UUID segmentId) {
        segments.deleteByIdAndOrgIdAndNotPrebuilt(segmentId, orgId);
    }

    static String name(String eventName, Target t) {
        String base = "Momentum: " + t.classKey() + " guests, " + t.genreFit() + " genre fit";
        String full = eventName == null || eventName.isBlank() ? base : base + " · " + eventName.trim();
        return full.length() <= MAX_NAME ? full : full.substring(0, MAX_NAME);
    }
}
