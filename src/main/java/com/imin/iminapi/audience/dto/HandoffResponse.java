package com.imin.iminapi.audience.dto;

import java.util.List;

/**
 * Response from the FR-SND-1 send-gate handoff endpoint.
 */
public record HandoffResponse(
        int recipientCount,
        List<String> selectedMembershipIds,
        List<ExclusionReason> excludedReasons,
        String campaignHubUrl
) {}
