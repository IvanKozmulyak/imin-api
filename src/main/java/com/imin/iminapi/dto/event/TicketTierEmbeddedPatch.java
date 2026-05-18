package com.imin.iminapi.dto.event;

import java.time.Instant;
import java.util.UUID;

public record TicketTierEmbeddedPatch(
        UUID id,                     // null = create new tier
        String name,
        Integer priceMinor,
        Integer quantity,
        Instant saleStartsAt,
        Boolean clearSaleStartsAt,
        Instant saleClosesAt,
        Boolean clearSaleClosesAt,
        Integer sortOrder,
        Boolean enabled
) {}
