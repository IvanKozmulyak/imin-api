package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.dto.AudiencePlanListItem;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.audienceplan.model.AudiencePlan;
import com.imin.iminapi.audienceplan.repository.AudiencePlanRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The org's upcoming events with their stored plan's status. Reads only: never computes or writes a plan. */
@Service
public class PlanListService {

    /** ponytail: the soonest 100 upcoming events; paging when an org plans more at once. */
    static final int LIMIT = 100;

    private final AudiencePlanAccess access;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final AudiencePlanRepository plans;
    private final CandidateLoader candidates;
    private final PlanService planService;
    private final Clock clock;

    public PlanListService(AudiencePlanAccess access, EventRepository events, TicketTierRepository tiers,
                           AudiencePlanRepository plans, CandidateLoader candidates, PlanService planService,
                           Clock clock) {
        this.access = access;
        this.events = events;
        this.tiers = tiers;
        this.plans = plans;
        this.candidates = candidates;
        this.planService = planService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AudiencePlanListItem> list(UUID orgId, String from) {
        access.requireEnabled(orgId);
        Instant now = clock.instant();
        Instant since = from(from, now);
        List<Event> upcoming = events.findUpcomingForPlans(orgId, since, PageRequest.of(0, LIMIT));
        if (upcoming.isEmpty()) return List.of();

        Map<UUID, AudiencePlan> current = new HashMap<>();
        for (AudiencePlan p : plans.findCurrentForEvents(orgId, upcoming.stream().map(Event::getId).toList())) {
            current.putIfAbsent(p.getEventId(), p);
        }
        // The org's mailable count is one input of every plan's hash, so it is read once.
        Integer mailable = current.isEmpty() ? null : candidates.mailableCount(orgId);

        Map<UUID, List<TicketTier>> tiersByEvent = current.isEmpty() ? Map.of()
                : tiers.findByEventIdInOrderBySortOrderAsc(current.keySet()).stream()
                        .collect(Collectors.groupingBy(TicketTier::getEventId));

        // A portrait depends only on (genre, city), so events sharing both read it once.
        Map<PortraitKey, List<AudiencePortraitResponse.NewPeopleGroup>> portraits = new HashMap<>();
        List<AudiencePlanListItem> out = new ArrayList<>(upcoming.size());
        for (Event e : upcoming) {
            AudiencePlan p = current.get(e.getId());
            String status = p == null ? "none"
                    : planService.isFresh(p, e, tiersByEvent.getOrDefault(e.getId(), List.of()), mailable,
                            portraits.computeIfAbsent(new PortraitKey(e.getGenreKey(), e.getVenueCityKey()),
                                    k -> planService.newPeople(e)))
                    ? "fresh" : "stale";
            out.add(new AudiencePlanListItem(e.getId(), e.getName(), e.getStartsAt(),
                    e.getStatus().name().toLowerCase(Locale.ROOT), status,
                    p == null ? null : p.getCoverageMid(),
                    p == null ? null : p.getVerdict(),
                    p == null ? null : p.getCreatedAt()));
        }
        return List.copyOf(out);
    }

    private record PortraitKey(String genre, String cityKey) {}

    /** Blank = now; a past instant is raised to now so started events never list. */
    static Instant from(String raw, Instant now) {
        if (raw == null || raw.isBlank()) return now;
        Instant parsed;
        try {
            parsed = Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                    Map.of("from", "must be an ISO-8601 instant, e.g. 2026-10-01T00:00:00Z"));
        }
        return parsed.isBefore(now) ? now : parsed;
    }
}
