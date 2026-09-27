package com.imin.iminapi.audienceplan.dto;

import java.util.List;
import java.util.UUID;

/**
 * The invited segments of an event: per segment its holdout (null when the segment was under the holdout minimum)
 * and one draft campaign per arm. {@code sendsEnabled} false means the drafts cannot be scheduled yet.
 */
public record AudiencePlanInvitationsResponse(UUID eventId, boolean sendsEnabled, List<Invitation> invitations) {

    /** {@code created} is false when the segment had already been invited and the stored ids are returned. */
    public record Invitation(String classKey, String genreFit, UUID planId, UUID planSegmentId, int members,
                             Holdout holdout, List<Arm> arms, boolean created) {}

    public record Holdout(UUID experimentId, int members) {}

    /**
     * {@code draftMissing} is true (and {@code segmentId}/{@code campaignId} null) once the organizer deleted the
     * arm's draft and it was not recreated.
     */
    public record Arm(String arm, UUID experimentId, int members, UUID segmentId, UUID campaignId,
                      boolean draftMissing) {}
}
