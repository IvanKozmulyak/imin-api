package com.imin.iminapi.dto;

import com.imin.iminapi.model.NotificationPreferences;

import java.util.UUID;

public record NotificationPreferencesDto(
        UUID userId,
        boolean ticketSold, boolean squadFormed, boolean predictorShift,
        boolean fillMilestone, boolean postEventReport, boolean campaignEnded,
        boolean payoutArrived) {
    public static NotificationPreferencesDto from(NotificationPreferences p) {
        return new NotificationPreferencesDto(p.getUserId(),
                p.isTicketSold(), p.isSquadFormed(), p.isPredictorShift(),
                p.isFillMilestone(), p.isPostEventReport(), p.isCampaignEnded(),
                p.isPayoutArrived());
    }
}
