package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.*;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.event.EventOverviewService;
import com.imin.iminapi.service.event.EventService;
import com.imin.iminapi.service.event.EventVelocityService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;
    private final EventOverviewService overviewService;
    private final EventVelocityService velocityService;

    public EventController(EventService eventService,
                           EventOverviewService overviewService,
                           EventVelocityService velocityService) {
        this.eventService = eventService;
        this.overviewService = overviewService;
        this.velocityService = velocityService;
    }

    @GetMapping
    public PageResponse<EventDto> list(@CurrentUser AuthPrincipal p,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        EventStatus s = (status == null || status.isBlank()) ? null : statusOr400(status);
        return eventService.list(p, s, page, pageSize);
    }

    /**
     * {@code EventStatus.fromWire} is a bare {@code valueOf}, so an unknown value threw
     * IllegalArgumentException — which no handler catches, making a stale bookmark or a typo'd
     * deep link a 500 INTERNAL plus a spurious log.error. Same translation the sibling
     * {@code EventMediaController.kindOr404} already does for MediaKind, and it names the
     * offending field so the FE can point at the filter.
     */
    private static EventStatus statusOr400(String status) {
        try {
            return EventStatus.fromWire(status);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "Unknown event status",
                    java.util.Map.of("status", "must be one of: draft, live, past, cancelled"));
        }
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
    public EventDto publish(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return eventService.publish(p, id);
    }

    @PostMapping("/{id}/unpublish")
    public EventDto unpublish(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return eventService.unpublish(p, id);
    }

    @GetMapping("/{id}/overview")
    public EventOverviewResponse overview(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return overviewService.overview(p, id);
    }

    @GetMapping("/{id}/sales-velocity")
    public EventVelocityService.VelocityResponse salesVelocity(
            @CurrentUser AuthPrincipal p,
            @PathVariable UUID id,
            @RequestParam(name = "days", required = false) Integer days) {
        int window = days == null ? EventVelocityService.DEFAULT_WINDOW_DAYS : days;
        return velocityService.windowEndingToday(p, id, window);
    }
}
