package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventWaitlistDto;
import com.imin.iminapi.dto.event.TicketTierDto;
import com.imin.iminapi.dto.event.WaitlistAddTicketsRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifyReleaseRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RoleGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The organizer's view of an event's notify-me list (Event › Tickets): who is still owed an email,
 * the last release email, and Add tickets. Counts only, never who is waiting.
 */
@Service
public class EventWaitlistService {

    static final int MAX_ADD_TICKETS = 500;

    private final EventRepository events;
    private final NotifySubscriptionRepository subscriptions;
    private final NotifyReleaseRepository releases;
    private final TicketTierService tierService;

    public EventWaitlistService(EventRepository events, NotifySubscriptionRepository subscriptions,
                                NotifyReleaseRepository releases, TicketTierService tierService) {
        this.events = events;
        this.subscriptions = subscriptions;
        this.releases = releases;
        this.tierService = tierService;
    }

    /** Any member of the event's org; another org's or a deleted event is 404. */
    @Transactional(readOnly = true)
    public EventWaitlistDto summary(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        EventWaitlistDto.LastEmail last = releases.findTopByEventIdOrderByFirstSentAtDesc(eventId)
                .map(r -> new EventWaitlistDto.LastEmail(r.getLastSentAt(), r.getSentCount()))
                .orElse(null);
        return new EventWaitlistDto(subscriptions.countPendingByEventId(eventId), last);
    }

    /** ADMIN or OWNER; 400 {@code fields.tierId|addTickets}; then the tier service's 404/409. */
    public TicketTierDto addTickets(AuthPrincipal p, UUID eventId, WaitlistAddTicketsRequest body) {
        RoleGuard.requireAtLeast(p, UserRole.ADMIN, "add tickets");
        Map<String, String> fields = new LinkedHashMap<>();
        if (body == null || body.tierId() == null) fields.put("tierId", "required");
        Integer n = body == null ? null : body.addTickets();
        if (n == null || n < 1 || n > MAX_ADD_TICKETS) fields.put("addTickets", "must be 1 to " + MAX_ADD_TICKETS);
        if (!fields.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid request body", fields);
        }
        return tierService.addQuantity(p, eventId, body.tierId(), n);
    }
}
