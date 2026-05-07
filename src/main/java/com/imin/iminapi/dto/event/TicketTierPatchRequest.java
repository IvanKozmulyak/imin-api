package com.imin.iminapi.dto.event;

import java.time.Instant;

public record TicketTierPatchRequest(
        String name,
        String kind,
        Integer priceMinor,
        Integer quantity,
        Instant saleClosesAt,
        Boolean clearSaleClosesAt,   // true = set saleClosesAt to null. Contradicts non-null saleClosesAt.
        Integer sortOrder,
        Boolean enabled
) {}
