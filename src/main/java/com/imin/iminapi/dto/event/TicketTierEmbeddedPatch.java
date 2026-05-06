package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
