package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.model.CampaignRecipient;

import java.time.Instant;
import java.util.UUID;

public record RecipientDto(UUID id, UUID membershipId, String email, String status,
                           String skipReason, String providerMessageId,
                           Instant openedAt, Instant clickedAt, Instant deliveredAt,
                           Instant lastEventAt) {
    public static RecipientDto from(CampaignRecipient r) {
        return new RecipientDto(r.getId(), r.getMembershipId(), r.getEmail(), r.getStatus(),
                r.getSkipReason(), r.getProviderMessageId(), r.getOpenedAt(), r.getClickedAt(),
                r.getDeliveredAt(), r.getLastEventAt());
    }
}
