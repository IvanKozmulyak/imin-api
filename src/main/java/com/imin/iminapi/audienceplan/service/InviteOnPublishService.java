package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsRequest;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsRequest.SegmentInvitation;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInviteOnPublishRequest;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInviteOnPublishResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInviteOnPublishResponse.InviteOnPublish;
import com.imin.iminapi.audienceplan.model.AudiencePlan;
import com.imin.iminapi.audienceplan.model.AudiencePlanSegment;
import com.imin.iminapi.audienceplan.model.PublishInvite;
import com.imin.iminapi.audienceplan.repository.AudiencePlanRepository;
import com.imin.iminapi.audienceplan.repository.AudiencePlanSegmentRepository;
import com.imin.iminapi.audienceplan.repository.PublishInviteRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Schedule invitations when I publish": stores the plan segments to invite on a draft event and, once the event is
 * published, runs {@link InvitationService} with them at least once (re-runs return the stored invitations). Drafts
 * only; the sends kill switch still applies.
 */
@Service
public class InviteOnPublishService {

    private static final Logger log = LoggerFactory.getLogger(InviteOnPublishService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<SegmentInvitation>> SEGMENTS = new TypeReference<>() {};
    /** A claim older than this without completion is taken as a crashed run. */
    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    // ponytail: 3 attempts, then dropped with a WARN (the Audience tab still invites); 50 intents per sweep pass.
    static final int MAX_ATTEMPTS = 3;
    static final int SWEEP_BATCH = 50;
    /** A never-claimed intent is swept only this long after its publish, so an old leftover invites nobody late. */
    static final Duration UNCLAIMED_EXPIRY = Duration.ofHours(48);
    /** A claim left on an event that is no longer live is purged after this long. */
    static final Duration CLAIMED_EXPIRY = Duration.ofDays(7);
    private static final List<EventStatus> NOT_LIVE = List.of(EventStatus.DRAFT, EventStatus.PAST, EventStatus.CANCELLED);

    private final AudiencePlanAccess access;
    private final EventRepository events;
    private final AudiencePlanRepository plans;
    private final AudiencePlanSegmentRepository planSegments;
    private final PublishInviteRepository invites;
    private final InvitationService invitations;
    private final PlanService planService;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InviteOnPublishService(AudiencePlanAccess access, EventRepository events, AudiencePlanRepository plans,
                                  AudiencePlanSegmentRepository planSegments, PublishInviteRepository invites,
                                  InvitationService invitations, PlanService planService,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.access = access;
        this.events = events;
        this.plans = plans;
        this.planSegments = planSegments;
        this.invites = invites;
        this.invitations = invitations;
        this.planService = planService;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AudiencePlanInviteOnPublishResponse get(AuthPrincipal principal, UUID eventId) {
        access.requireEnabled(principal.orgId());
        Event event = event(principal.orgId(), eventId);
        return response(event.getId(), invites.findByEventIdAndOrgId(event.getId(), principal.orgId()));
    }

    @Transactional
    public AudiencePlanInviteOnPublishResponse put(AuthPrincipal principal, UUID eventId,
                                                   AudiencePlanInviteOnPublishRequest request) {
        UUID orgId = principal.orgId();
        access.requireEnabled(orgId);
        Event event = draft(orgId, eventId);
        AudiencePlan plan = plans.findFirstByOrgIdAndEventIdAndSupersededByIsNullOrderByCreatedAtDesc(orgId, eventId)
                .orElseThrow(() -> ApiException.notFound("Audience plan"));
        List<SegmentInvitation> segments = validate(request, planSegments.findByPlanIdOrderByPositionAsc(plan.getId()));

        PublishInvite row = invites.findByEventIdAndOrgId(eventId, orgId).orElseGet(PublishInvite::new);
        row.setEventId(event.getId());
        row.setOrgId(orgId);
        row.setSegments(json(segments));
        row.setCreatedBy(principal.userId());
        row.setUpdatedAt(clock.instant());
        // A new intent waits for its own publish; a claim left from an earlier publish no longer applies.
        row.setClaimedAt(null);
        row.setAttempts(0);
        return response(event.getId(), Optional.of(invites.save(row)));
    }

    @Transactional
    public void delete(AuthPrincipal principal, UUID eventId) {
        access.requireEnabled(principal.orgId());
        draft(principal.orgId(), eventId);
        invites.deleteByEventId(eventId);
    }

    /** Runs the intent stored for this publish, unless a run already holds it (the sweeper takes a stale one). */
    public void runOnPublish(UUID eventId) {
        Instant now = now();
        Integer claimed = tx.execute(s -> invites.claim(eventId, now));
        if (claimed == null || claimed == 0) return;
        run(eventId, now);
    }

    /**
     * Purges expired intents, then re-runs published events' intents claimed over {@link #STALE_AFTER} ago, or never
     * claimed within {@link #UNCLAIMED_EXPIRY} of the publish (refreshed first);
     * safe because a stored invitation is returned, never created twice. Drops one at {@link #MAX_ATTEMPTS}.
     */
    public int sweepStale() {
        Instant now = now();
        Instant cutoff = now.minus(STALE_AFTER);
        Instant expiry = now.minus(UNCLAIMED_EXPIRY);
        purge(now, expiry);
        List<PublishInvite> stale = tx.execute(s -> invites.findStale(EventStatus.LIVE, cutoff, expiry,
                PageRequest.of(0, SWEEP_BATCH)));
        int rerun = 0;
        for (PublishInvite row : stale == null ? List.<PublishInvite>of() : stale) {
            UUID eventId = row.getEventId();
            Instant was = row.getClaimedAt();
            try {
                if (row.getAttempts() >= MAX_ATTEMPTS) {
                    Integer dropped = tx.execute(s -> invites.complete(eventId, was));
                    if (dropped != null && dropped == 1) {
                        log.warn("InviteOnPublish: event {} intent dropped after {} attempts", eventId, row.getAttempts());
                    }
                    continue;
                }
                Instant mine = now();
                Integer taken = tx.execute(s -> was == null ? invites.claim(eventId, mine)
                        : invites.reclaim(eventId, was, mine));
                if (taken == null || taken == 0) continue;
                if (was == null) refreshPlan(eventId);
                log.info("InviteOnPublish: event {} intent re-run (attempt {})", eventId, row.getAttempts() + 1);
                run(eventId, mine);
                rerun++;
            } catch (Exception e) {
                log.warn("InviteOnPublish: event {} re-run failed: {} {}", eventId, e.getClass().getSimpleName(),
                        LogSafe.redact(e.getMessage()));
            }
        }
        return rerun;
    }

    /**
     * One invitation call per segment, so one refusal blocks no other. Deleted once every segment was invited or
     * refused; an unexpected failure leaves the claim for the sweeper.
     */
    private void run(UUID eventId, Instant claimedAt) {
        PublishInvite claimed = tx.execute(s -> invites.findById(eventId).orElse(null));
        if (claimed == null || !claimedAt.equals(claimed.getClaimedAt())) return;
        Event event = events.findActive(eventId).filter(e -> claimed.getOrgId().equals(e.getOrgId())).orElse(null);
        if (event == null || !access.isEnabled(claimed.getOrgId())) {
            log.info("InviteOnPublish: event {} gone or its org is off; stored invitations dropped", eventId);
            complete(eventId, claimedAt);
            return;
        }
        Optional<AudiencePlan> plan = plans.findFirstByOrgIdAndEventIdAndSupersededByIsNullOrderByCreatedAtDesc(
                claimed.getOrgId(), eventId);
        if (plan.isEmpty()) {
            log.warn("InviteOnPublish: event {} has no plan; nothing invited", eventId);
            complete(eventId, claimedAt);
            return;
        }
        List<AudiencePlanSegment> shown = planSegments.findByPlanIdOrderByPositionAsc(plan.get().getId());
        Instant now = now();
        // The role is a placeholder: invitations are open to any org member and the audit reads only the ids.
        AuthPrincipal actor = new AuthPrincipal(claimed.getCreatedBy(), claimed.getOrgId(), UserRole.MEMBER, null);
        boolean retry = false;
        for (SegmentInvitation s : read(claimed.getSegments())) {
            String key = s.classKey() + "/" + s.genreFit();
            if (!isShown(shown, s)) {
                log.info("InviteOnPublish: event {} segment {} no longer in the plan; skipped", eventId, key);
                continue;
            }
            // Arms a new invitation can no longer get are dropped; the segment still goes out on the others.
            List<String> arms = invitations.offeredArms(event, plan.get(), s.arms(), now);
            if (arms.isEmpty()) {
                log.info("InviteOnPublish: event {} segment {} has no arm left that is still offered; skipped",
                        eventId, key);
                continue;
            }
            if (arms.size() < s.arms().size()) {
                log.info("InviteOnPublish: event {} segment {} arms {} no longer offered; invited on {}", eventId, key,
                        s.arms().stream().filter(a -> !arms.contains(a)).toList(), arms);
            }
            SegmentInvitation one = new SegmentInvitation(s.classKey(), s.genreFit(), arms, s.holdoutPct());
            try {
                invitations.invite(actor, eventId, new AudiencePlanInvitationsRequest(List.of(one), null));
            } catch (ApiException e) {
                if (e.code() == ErrorCode.AUDIENCE_PLAN_ALREADY_INVITED) {
                    log.info("InviteOnPublish: event {} segment {} already invited with other settings; kept",
                            eventId, key);
                } else if (e.status().is5xxServerError()) {
                    retry = true;
                    log.warn("InviteOnPublish: event {} segment {} failed, left for a re-run: {} {}", eventId, key,
                            e.code(), LogSafe.redact(e.getMessage()));
                } else {
                    log.warn("InviteOnPublish: event {} segment {} not invited: {} {}", eventId, key, e.code(),
                            LogSafe.redact(e.getMessage()));
                }
            } catch (Exception e) {
                retry = true;
                log.warn("InviteOnPublish: event {} segment {} failed, left for a re-run: {} {}", eventId, key,
                        e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
            }
        }
        if (!retry) complete(eventId, claimedAt);
    }

    /**
     * Deletes never-claimed intents of events published over {@link #UNCLAIMED_EXPIRY} ago, and claims left over
     * {@link #CLAIMED_EXPIRY} on events that are no longer live.
     */
    private void purge(Instant now, Instant expiry) {
        List<UUID> expired = tx.execute(s -> invites.findExpiredUnclaimed(EventStatus.LIVE, expiry,
                PageRequest.of(0, SWEEP_BATCH)));
        for (UUID eventId : expired == null ? List.<UUID>of() : expired) {
            Integer deleted = tx.execute(s -> invites.deleteUnclaimed(eventId));
            if (deleted != null && deleted == 1) {
                log.info("InviteOnPublish: event {} intent never ran within {}h of its publish; dropped", eventId,
                        UNCLAIMED_EXPIRY.toHours());
            }
        }
        Integer leftover = tx.execute(s -> invites.deleteClaimedOf(NOT_LIVE, now.minus(CLAIMED_EXPIRY)));
        if (leftover != null && leftover > 0) {
            log.info("InviteOnPublish: dropped {} claimed intent(s) of events no longer live", leftover);
        }
    }

    private void complete(UUID eventId, Instant claimedAt) {
        tx.execute(s -> invites.complete(eventId, claimedAt));
    }

    private void refreshPlan(UUID eventId) {
        try {
            planService.refresh(eventId);
        } catch (Exception e) {
            log.warn("InviteOnPublish: plan refresh before the re-run of event {} failed: {} {}", eventId,
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    /** Micros, as stored, so a claim read back compares equal. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Event event(UUID orgId, UUID eventId) {
        return events.findActive(eventId).filter(e -> orgId.equals(e.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("Audience plan"));
    }

    /** Only a draft can carry the intent; once published the organizer invites directly. */
    private Event draft(UUID orgId, UUID eventId) {
        Event event = event(orgId, eventId);
        if (event.getStatus() != EventStatus.DRAFT) {
            throw ApiException.invalidState("The event is published; invite from the audience plan instead");
        }
        return event;
    }

    private static List<SegmentInvitation> validate(AudiencePlanInviteOnPublishRequest request,
                                                    List<AudiencePlanSegment> shown) {
        if (request == null || request.segments() == null || request.segments().isEmpty()) {
            throw invalid("segments", "at least one segment is required");
        }
        Set<String> seen = new HashSet<>();
        List<SegmentInvitation> out = new ArrayList<>();
        for (int i = 0; i < request.segments().size(); i++) {
            String field = "segments[" + i + "]";
            SegmentInvitation s = request.segments().get(i);
            if (s == null) throw invalid(field, "must not be null");
            if (!isShown(shown, s)) {
                throw invalid(field, "not a segment of the current plan: " + s.classKey() + "/" + s.genreFit());
            }
            if (!seen.add(s.classKey() + "/" + s.genreFit())) throw invalid(field, "segment listed twice");
            Integer pct = s.holdoutPct();
            if (pct != null && (pct < InvitationService.HOLDOUT_PCT_MIN || pct > InvitationService.HOLDOUT_PCT_MAX)) {
                throw invalid(field + ".holdoutPct", "must be between " + InvitationService.HOLDOUT_PCT_MIN
                        + " and " + InvitationService.HOLDOUT_PCT_MAX);
            }
            List<String> arms = InvitationService.arms(field + ".arms", s.arms());
            out.add(new SegmentInvitation(s.classKey(), s.genreFit(), arms, pct));
        }
        return List.copyOf(out);
    }

    private static boolean isShown(List<AudiencePlanSegment> shown, SegmentInvitation s) {
        return shown.stream().anyMatch(p -> p.getClassKey().equals(s.classKey()) && p.getGenreFit().equals(s.genreFit()));
    }

    private static AudiencePlanInviteOnPublishResponse response(UUID eventId, Optional<PublishInvite> row) {
        return new AudiencePlanInviteOnPublishResponse(eventId,
                row.map(r -> new InviteOnPublish(read(r.getSegments()), r.getUpdatedAt())).orElse(null));
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed", Map.of(field, message));
    }

    private static String json(List<SegmentInvitation> segments) {
        try {
            return JSON.writeValueAsString(segments);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write publish invitations", e);
        }
    }

    private static List<SegmentInvitation> read(String json) {
        try {
            return JSON.readValue(json, SEGMENTS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read publish invitations", e);
        }
    }
}
