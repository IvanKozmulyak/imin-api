package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.model.CampaignRecipient;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a campaign's recipient log.
 *
 * <p>{@code name} is the joined membership display name, resolved by the service from an
 * org-scoped batch lookup. It is NULL — never a placeholder string — whenever a name cannot
 * be established: {@code membershipId} is null (DSAR erased the membership; the V53 FK is
 * {@code ON DELETE SET NULL}), the membership carries no display name, or the row somehow
 * points outside the caller's org. The client decides how to render absence.
 *
 * <p>{@code status} is the lifecycle only (pending|sent|delivered|bounced|failed|complained|
 * unsubscribed|skipped). Engagement lives in {@code openedAt}/{@code clickedAt} and is
 * orthogonal — a row can be {@code delivered} AND opened.
 */
public record RecipientDto(UUID id, UUID membershipId, String email, String name, String status,
                           String skipReason, String providerMessageId,
                           Instant openedAt, Instant clickedAt, Instant deliveredAt,
                           Instant lastEventAt) {

    /** @param name resolved membership display name, or {@code null} when unavailable. */
    public static RecipientDto from(CampaignRecipient r, String name) {
        return new RecipientDto(r.getId(), r.getMembershipId(), r.getEmail(), name, r.getStatus(),
                r.getSkipReason(), r.getProviderMessageId(), r.getOpenedAt(), r.getClickedAt(),
                r.getDeliveredAt(), r.getLastEventAt());
    }
}
