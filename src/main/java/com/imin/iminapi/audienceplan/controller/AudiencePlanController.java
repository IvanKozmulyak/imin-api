package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.AudiencePlanRecomputeRequest;
import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;
import com.imin.iminapi.audienceplan.service.PlanService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** An event's audience plan for the caller's org; {@code locale} only selects the summary text. */
@RestController
@RequestMapping("/api/v1/events")
public class AudiencePlanController {

    private final PlanService plans;

    public AudiencePlanController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping("/{eventId}/audience-plan")
    public AudiencePlanResponse current(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                        @RequestParam(required = false) String locale) {
        return plans.current(p.orgId(), eventId, locale);
    }

    @PostMapping("/{eventId}/audience-plan")
    public AudiencePlanResponse recompute(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                          @RequestParam(required = false) String locale,
                                          @Valid @RequestBody(required = false) AudiencePlanRecomputeRequest body) {
        return plans.recompute(p.orgId(), eventId, body, locale);
    }
}
