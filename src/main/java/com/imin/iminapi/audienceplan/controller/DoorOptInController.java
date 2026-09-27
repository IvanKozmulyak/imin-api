package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.DoorOptInSettingsRequest;
import com.imin.iminapi.audienceplan.dto.DoorOptInSettingsResponse;
import com.imin.iminapi.audienceplan.service.DoorOptInService;
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

/** Organizer switch for an event's door QR page, its URL and the sign-up count. */
@RestController
@RequestMapping("/api/v1/events/{eventId}/door-optin")
public class DoorOptInController {

    private final DoorOptInService service;

    public DoorOptInController(DoorOptInService service) {
        this.service = service;
    }

    @GetMapping
    public DoorOptInSettingsResponse get(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return service.settings(p, eventId);
    }

    @PutMapping
    public DoorOptInSettingsResponse put(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                         @Valid @RequestBody DoorOptInSettingsRequest body) {
        return service.setEnabled(p, eventId, body.enabled());
    }
}
