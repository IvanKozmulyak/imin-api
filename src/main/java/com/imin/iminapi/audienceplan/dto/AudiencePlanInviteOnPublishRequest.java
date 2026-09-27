package com.imin.iminapi.audienceplan.dto;

import com.imin.iminapi.audienceplan.dto.AudiencePlanInvitationsRequest.SegmentInvitation;

import java.util.List;

/** Plan segments to invite when the draft is published; same segment shape as the invitations request. */
public record AudiencePlanInviteOnPublishRequest(List<SegmentInvitation> segments) {}
