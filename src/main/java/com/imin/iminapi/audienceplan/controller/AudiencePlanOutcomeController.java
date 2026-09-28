package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.AudiencePlanOutcomeResponse;
import com.imin.iminapi.audienceplan.service.OutcomeService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** What an event's invitation arms did against their holdouts; read-only. */
@RestController
@RequestMapping("/api/v1/events")
public class AudiencePlanOutcomeController {

    private final OutcomeService outcomes;

    public AudiencePlanOutcomeController(OutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    @GetMapping("/{eventId}/audience-plan/outcome")
    public AudiencePlanOutcomeResponse outcome(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return outcomes.outcome(p.orgId(), eventId);
    }
}
