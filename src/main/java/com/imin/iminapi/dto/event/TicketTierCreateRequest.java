package com.imin.iminapi.dto.event;

import java.time.Instant;

public record TicketTierCreateRequest(
        String name,
        Integer priceMinor,
        Integer quantity,
        Instant saleStartsAt,
        Instant saleClosesAt,
        Integer sortOrder,     // optional — null defaults to max(existing)+1 in service
        Boolean enabled        // optional — null defaults to true
) {}
