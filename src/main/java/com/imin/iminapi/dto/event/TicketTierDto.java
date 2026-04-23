package com.imin.iminapi.dto.event;

import com.imin.iminapi.model.TicketTier;

import java.time.Instant;
import java.util.UUID;

public record TicketTierDto(
        UUID id, UUID eventId, String name, String kind,
        int priceMinor, int quantity, int sold,
        Instant saleClosesAt, boolean enabled, int sortOrder) {
    public static TicketTierDto from(TicketTier t) {
        return new TicketTierDto(t.getId(), t.getEventId(), t.getName(), t.getKind().wireValue(),
                t.getPriceMinor(), t.getQuantity(), t.getSold(),
                t.getSaleClosesAt(), t.isEnabled(), t.getSortOrder());
    }
}
