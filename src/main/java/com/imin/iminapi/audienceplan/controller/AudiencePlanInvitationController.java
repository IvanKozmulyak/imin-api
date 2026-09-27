package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsRequest;
import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsResponse;
import com.imin.iminapi.audienceplan.service.InvitationService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Invites segments of an event's current audience plan; creates drafts only, never sends. */
@RestController
@RequestMapping("/api/v1/events")
public class AudiencePlanInvitationController {

    private final InvitationService invitations;

    public AudiencePlanInvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/{eventId}/audience-plan/invitations")
    public AudiencePlanInvitationsResponse invite(@CurrentUser AuthPrincipal p, @PathVariable UUID eventId,
                                                  @RequestBody(required = false) AudiencePlanInvitationsRequest body) {
        return invitations.invite(p, eventId, body);
    }
}
