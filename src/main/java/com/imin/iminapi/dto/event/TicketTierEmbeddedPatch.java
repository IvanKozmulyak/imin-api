package com.imin.iminapi.dto.event;

import java.time.Instant;
import java.util.UUID;

public record TicketTierEmbeddedPatch(
        UUID id,                     // null = create new tier
        String name,
        String kind,
        Integer priceMinor,
        Integer quantity,
        Instant saleClosesAt,
        Boolean clearSaleClosesAt,
        Integer sortOrder,
        Boolean enabled
) {}
