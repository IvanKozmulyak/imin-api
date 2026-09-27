package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.TimingArm;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Schedule invitations when I publish": stores the plan segments to invite on a draft event and, once the event is
 * published, runs {@link InvitationService} with them one time. Drafts only; the sends kill switch still applies.
 */
@Service
public class InviteOnPublishService {

    private static final Logger log = LoggerFactory.getLogger(InviteOnPublishService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<SegmentInvitation>> SEGMENTS = new TypeReference<>() {};
    private static final String D3 = TimingArm.D3.name().toLowerCase(Locale.ROOT);

    private final AudiencePlanAccess access;
    private final EventRepository events;
    private final AudiencePlanRepository plans;
    private final AudiencePlanSegmentRepository planSegments;
    private final PublishInviteRepository invites;
    private final InvitationService invitations;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InviteOnPublishService(AudiencePlanAccess access, EventRepository events, AudiencePlanRepository plans,
                                  AudiencePlanSegmentRepository planSegments, PublishInviteRepository invites,
                                  InvitationService invitations, PlatformTransactionManager transactionManager,
                                  Clock clock) {
        this.access = access;
        this.events = events;
        this.plans = plans;
        this.planSegments = planSegments;
        this.invites = invites;
        this.invitations = invitations;
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
        return response(event.getId(), Optional.of(invites.save(row)));
    }

    @Transactional
    public void delete(AuthPrincipal principal, UUID eventId) {
        access.requireEnabled(principal.orgId());
        draft(principal.orgId(), eventId);
        invites.deleteByEventId(eventId);
    }

    /** Claims (deletes) the intent so it runs once; one invitation call per segment, so one refusal blocks no other. */
    // ponytail: a failed run is not retried; the organizer can still invite from the Audience tab.
    public void runOnPublish(UUID eventId) {
        PublishInvite claimed = tx.execute(s -> {
            Optional<PublishInvite> row = invites.findById(eventId);
            if (row.isEmpty() || invites.deleteByEventId(eventId) == 0) return null;
            return row.get();
        });
        if (claimed == null) return;
        Event event = events.findActive(eventId).filter(e -> claimed.getOrgId().equals(e.getOrgId())).orElse(null);
        if (event == null || !access.isEnabled(claimed.getOrgId())) {
            log.info("InviteOnPublish: event {} gone or its org is off; stored invitations dropped", eventId);
            return;
        }
        Optional<AudiencePlan> plan = plans.findFirstByOrgIdAndEventIdAndSupersededByIsNullOrderByCreatedAtDesc(
                claimed.getOrgId(), eventId);
        if (plan.isEmpty()) {
            log.warn("InviteOnPublish: event {} has no plan; nothing invited", eventId);
            return;
        }
        List<AudiencePlanSegment> shown = planSegments.findByPlanIdOrderByPositionAsc(plan.get().getId());
        boolean hasD3 = plan.get().getD3Date() != null;
        // The role is a placeholder: invitations are open to any org member and the audit reads only the ids.
        AuthPrincipal actor = new AuthPrincipal(claimed.getCreatedBy(), claimed.getOrgId(), UserRole.MEMBER, null);
        for (SegmentInvitation s : read(claimed.getSegments())) {
            String key = s.classKey() + "/" + s.genreFit();
            if (!isShown(shown, s)) {
                log.info("InviteOnPublish: event {} segment {} no longer in the plan; skipped", eventId, key);
                continue;
            }
            List<String> arms = s.arms().stream().filter(a -> hasD3 || !D3.equals(a)).toList();
            if (arms.isEmpty()) {
                log.info("InviteOnPublish: event {} segment {} has no arm left without D-3; skipped", eventId, key);
                continue;
            }
            SegmentInvitation one = new SegmentInvitation(s.classKey(), s.genreFit(), arms, s.holdoutPct());
            try {
                invitations.invite(actor, eventId, new AudiencePlanInvitationsRequest(List.of(one), null));
            } catch (Exception e) {
                log.warn("InviteOnPublish: event {} segment {} not invited: {} {}", eventId, key,
                        e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
            }
        }
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
