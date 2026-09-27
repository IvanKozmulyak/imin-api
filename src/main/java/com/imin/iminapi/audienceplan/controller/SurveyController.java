package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.SurveySettingsRequest;
import com.imin.iminapi.audienceplan.dto.SurveySettingsResponse;
import com.imin.iminapi.audienceplan.service.SurveyService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Organizer switch for an event's post-event survey, its URL and the answer count. */
@RestController
@RequestMapping("/api/v1/events/{eventId}/survey")
public class SurveyController {

    private final SurveyService service;

    public SurveyController(SurveyService service) {
        this.service = service;
    }

    @GetMapping
    public SurveySettingsResponse get(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return service.settings(p, eventId);
    }

    @PutMapping
    public SurveySettingsResponse put(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                      @Valid @RequestBody SurveySettingsRequest body) {
        return service.setEnabled(p, eventId, body.enabled());
    }
}
