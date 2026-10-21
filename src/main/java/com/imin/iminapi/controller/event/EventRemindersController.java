package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.EventRemindersRequest;
import com.imin.iminapi.dto.event.EventRemindersResponse;
import com.imin.iminapi.dto.event.ReminderPreviewResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.EventRemindersService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Organizer controls and results of the pre-event reminders for one event. */
@RestController
@RequestMapping("/api/v1/events/{eventId}/reminders")
public class EventRemindersController {

    private final EventRemindersService service;

    public EventRemindersController(EventRemindersService service) {
        this.service = service;
    }

    @GetMapping
    public EventRemindersResponse get(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return service.settings(p, eventId);
    }

    @PutMapping
    public EventRemindersResponse put(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                      @RequestBody EventRemindersRequest body) {
        return service.update(p, eventId, body);
    }

    @GetMapping("/preview")
    public ReminderPreviewResponse preview(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                           @RequestParam String window,
                                           @RequestParam(required = false) String locale) {
        return service.preview(p, eventId, window, locale);
    }
}
