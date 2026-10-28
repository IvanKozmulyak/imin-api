package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.EventWaitlistDto;
import com.imin.iminapi.dto.event.TicketTierDto;
import com.imin.iminapi.dto.event.WaitlistAddTicketsRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.EventWaitlistService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Organizer notify-me list for one event: counts, last release email, Add tickets. */
@RestController
@RequestMapping("/api/v1/events/{eventId}/waitlist")
public class EventWaitlistController {

    private final EventWaitlistService waitlist;

    public EventWaitlistController(EventWaitlistService waitlist) {
        this.waitlist = waitlist;
    }

    /** 200 counts; 404 for another org's or a deleted event. */
    @GetMapping
    public EventWaitlistDto get(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return waitlist.summary(p, eventId);
    }

    /**
     * 200 the updated tier. 403 below ADMIN; 400 INVALID_REQUEST fields.tierId|addTickets;
     * 404 another org's event or a tier of another event; 409 INVALID_STATE fields.event=not_live.
     */
    @PostMapping("/tickets")
    public TicketTierDto addTickets(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                    @RequestBody(required = false) WaitlistAddTicketsRequest body) {
        return waitlist.addTickets(p, eventId, body);
    }
}
