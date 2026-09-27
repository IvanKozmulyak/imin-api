package com.imin.iminapi.audienceplan.dto;

import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsRequest.SegmentInvitation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code inviteOnPublish} is null when nothing is scheduled for the publish. */
public record AudiencePlanInviteOnPublishResponse(UUID eventId, InviteOnPublish inviteOnPublish) {

    public record InviteOnPublish(List<SegmentInvitation> segments, Instant updatedAt) {}
}
