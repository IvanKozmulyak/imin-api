package com.imin.iminapi.buyer.dto;

import com.imin.iminapi.buyer.model.BuyerSavedEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /buyer/saved}. Ids only — the buyer site already has
 * an event-detail endpoint and hydrates from it, and returning event bodies
 * here would put unpublished titles behind an endpoint with no visibility
 * rules of its own.
 */
public record BuyerSavedResponse(UUID eventId, Instant savedAt) {

    public static BuyerSavedResponse of(BuyerSavedEvent row) {
        return new BuyerSavedResponse(row.getEventId(), row.getCreatedAt());
    }
}
