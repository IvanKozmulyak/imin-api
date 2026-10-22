package com.imin.iminapi.marketing.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.marketing.service.MarketingPauseService;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.ErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Staff-only lift of a breaker pause, on the normal dashboard login plus the staff allow-list
 * ({@code imin.moderation.staff-user-ids}). Responses: 200 resumed, 400 {@code FIELD_INVALID} (note over 1000
 * characters or malformed JSON), 404 for non-staff whatever the path or body, and for an unknown org, 409
 * {@code INVALID_STATE} when the org is not paused. Hidden from the published OpenAPI.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/internal/marketing/orgs")
public class InternalMarketingController {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final MarketingPauseService service;

    public InternalMarketingController(MarketingPauseService service) {
        this.service = service;
    }

    public record ResumeRequest(String note) {}

    // The id and body arrive raw and are parsed only after the staff check, so a non-staff caller gets the same 404.
    @PostMapping("/{orgId}/resume")
    public MarketingPauseService.Resumed resume(@CurrentUser AuthPrincipal p, @PathVariable String orgId,
                                                @RequestBody(required = false) String body) {
        service.requireStaff(p);
        UUID id;
        try {
            id = UUID.fromString(orgId);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Organization");
        }
        ResumeRequest req = parse(body);
        return service.resume(p, id, req == null ? null : req.note());
    }

    private static ResumeRequest parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return JSON.readValue(body, ResumeRequest.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Invalid resume",
                    Map.of("body", "malformed JSON"));
        }
    }
}
