package com.imin.iminapi.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.ErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff-only moderation holds, on the normal dashboard login plus the staff allow-list.
 * Responses: 201 placed (with warnings), 200 lifted / listed, 400 {@code FIELD_INVALID},
 * 404 for non-staff (whatever the body or path holds), unknown org, an event of another org or an unknown hold,
 * 409 {@code INVALID_STATE} when the hold was already lifted or a payout hold meets an org that is not on manual
 * payouts. Hidden from the published OpenAPI.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/internal/moderation/holds")
public class InternalModerationController {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ModerationHoldService service;

    public InternalModerationController(ModerationHoldService service) {
        this.service = service;
    }

    public record LiftRequest(String note) {}

    // Bodies, ids and params arrive raw and are parsed only after the staff check, so a non-staff caller
    // gets the same 404 whatever it sends.

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModerationHoldService.Placed place(@CurrentUser AuthPrincipal p, @RequestBody(required = false) String body) {
        service.requireStaff(p);
        return service.place(p, parse(body, ModerationHoldService.PlaceRequest.class));
    }

    @PostMapping("/{id}/lift")
    public ModerationHoldService.HoldView lift(@CurrentUser AuthPrincipal p, @PathVariable String id,
                                               @RequestBody(required = false) String body) {
        service.requireStaff(p);
        UUID holdId = uuidOrNotFound(id);
        LiftRequest req = parse(body, LiftRequest.class);
        return service.lift(p, holdId, req == null ? null : req.note());
    }

    @GetMapping
    public List<ModerationHoldService.HoldView> list(@CurrentUser AuthPrincipal p,
                                                     @RequestParam(required = false) String orgId) {
        service.requireStaff(p);
        UUID org;
        try {
            org = orgId == null || orgId.isBlank() ? null : UUID.fromString(orgId.strip());
        } catch (IllegalArgumentException e) {
            throw invalid("orgId", "must be a UUID");
        }
        return service.listForOrg(p, org);
    }

    private static <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank()) return null;
        try {
            return JSON.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw invalid("body", "malformed JSON");
        }
    }

    private static UUID uuidOrNotFound(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Hold");
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Invalid hold", Map.of(field, message));
    }
}
