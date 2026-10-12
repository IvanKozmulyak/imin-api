package com.imin.iminapi.cancellation;

import com.imin.iminapi.cancellation.dto.CancelEventRequest;
import com.imin.iminapi.cancellation.dto.EventCancellationPreviewResponse;
import com.imin.iminapi.cancellation.dto.EventCancellationResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Cancel a published event and follow its refunds. Cancel is owner/admin and idempotent (a repeat answers the
 * existing cancellation); preview and status are for any member. Another org's event is 404.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}")
public class EventCancellationController {

    private final EventCancellationService service;

    public EventCancellationController(EventCancellationService service) {
        this.service = service;
    }

    @PostMapping("/cancel")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled (or already was); refunds run in the background",
                    content = @Content(schema = @Schema(implementation = EventCancellationResponse.class))),
            @ApiResponse(responseCode = "400", description = "FIELD_INVALID: confirm must be true"),
            @ApiResponse(responseCode = "403", description = "FORBIDDEN: only an owner or admin can cancel"),
            @ApiResponse(responseCode = "404", description = "NOT_FOUND: no such event in this org"),
            @ApiResponse(responseCode = "409", description = "INVALID_STATE: not published or already ended; "
                    + "EVENT_CANCEL_PLAN_CHANGED: orders changed since the preview (fields.orders), event still live")})
    public EventCancellationResponse cancel(@PathVariable UUID eventId, @CurrentUser AuthPrincipal principal,
                                            @RequestBody(required = false) CancelEventRequest body) {
        return service.cancel(principal, eventId, body);
    }

    @GetMapping("/cancellation/preview")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What a cancel would refund now",
                    content = @Content(schema = @Schema(implementation = EventCancellationPreviewResponse.class))),
            @ApiResponse(responseCode = "404", description = "NOT_FOUND: no such event in this org")})
    public EventCancellationPreviewResponse preview(@PathVariable UUID eventId, @CurrentUser AuthPrincipal principal) {
        return service.preview(principal, eventId);
    }

    @GetMapping("/cancellation")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refund progress of the cancellation",
                    content = @Content(schema = @Schema(implementation = EventCancellationResponse.class))),
            @ApiResponse(responseCode = "404", description = "NOT_FOUND: no such event in this org, or not cancelled")})
    public EventCancellationResponse status(@PathVariable UUID eventId, @CurrentUser AuthPrincipal principal) {
        return service.status(principal, eventId);
    }
}
