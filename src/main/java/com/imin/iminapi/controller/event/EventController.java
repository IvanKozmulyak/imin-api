package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.*;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.EventOverviewService;
import com.imin.iminapi.service.event.EventService;
import com.imin.iminapi.web.IdempotencyKeySupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;
    private final EventOverviewService overviewService;
    private final IdempotencyKeySupport idempotency;

    @Autowired @Lazy
    private com.fasterxml.jackson.databind.ObjectMapper om;

    public EventController(EventService eventService,
                           EventOverviewService overviewService,
                           IdempotencyKeySupport idempotency) {
        this.eventService = eventService;
        this.overviewService = overviewService;
        this.idempotency = idempotency;
    }

    @GetMapping
    public PageResponse<EventDto> list(@CurrentUser AuthPrincipal p,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        EventStatus s = (status == null || status.isBlank()) ? null : EventStatus.fromWire(status);
        return eventService.list(p, s, page, pageSize);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventDto create(@CurrentUser AuthPrincipal p, @RequestBody(required = false) EventPatchRequest body) {
        return eventService.createDraft(p, body);
    }

    @GetMapping("/{id}")
    public EventDto detail(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return eventService.detail(p, id);
    }

    @PatchMapping("/{id}")
    public EventDto patch(@CurrentUser AuthPrincipal p,
                          @PathVariable UUID id,
                          @RequestHeader(value = "If-Match", required = false) String ifMatch,
                          @RequestBody EventPatchRequest body) {
        return eventService.patch(p, id, ifMatch, body);
    }

    @PostMapping("/{id}/publish")
    public EventDto publish(@CurrentUser AuthPrincipal p,
                            @PathVariable UUID id,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key == null || key.isBlank()) return eventService.publish(p, id);
        var route = "POST /api/v1/events/:id/publish";
        var cached = idempotency.runOrReplay(p.orgId(), route, key,
                () -> idempotency.toCached(200, eventService.publish(p, id)));
        try {
            return om.readValue(cached.bodyJson(), EventDto.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialise cached publish response", e);
        }
    }

    @GetMapping("/{id}/overview")
    public EventOverviewResponse overview(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return overviewService.overview(p, id);
    }
}
