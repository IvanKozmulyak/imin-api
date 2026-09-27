package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.AudiencePlanInviteOnPublishRequest;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInviteOnPublishResponse;
import com.imin.iminapi.audienceplan.service.InviteOnPublishService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Invitations a draft event creates when it is published; drafts only, never sends. */
@RestController
@RequestMapping("/api/v1/events")
public class AudiencePlanInviteOnPublishController {

    private final InviteOnPublishService service;

    public AudiencePlanInviteOnPublishController(InviteOnPublishService service) {
        this.service = service;
    }

    @GetMapping("/{eventId}/audience-plan/invite-on-publish")
    public AudiencePlanInviteOnPublishResponse get(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        return service.get(p, eventId);
    }

    @PutMapping("/{eventId}/audience-plan/invite-on-publish")
    public AudiencePlanInviteOnPublishResponse put(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                                   @RequestBody(required = false) AudiencePlanInviteOnPublishRequest body) {
        return service.put(p, eventId, body);
    }

    @DeleteMapping("/{eventId}/audience-plan/invite-on-publish")
    public ResponseEntity<Void> delete(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId) {
        service.delete(p, eventId);
        return ResponseEntity.noContent().build();
    }
}
