package com.imin.iminapi.dto.event;

import java.time.Instant;

public record TicketTierCreateRequest(
        String name,
        String kind,           // TicketTierKind wire value: "earlyBird" | "standard" | "lateBird" | "custom"
        Integer priceMinor,
        Integer quantity,
        Instant saleClosesAt,
        Integer sortOrder,     // optional — null defaults to max(existing)+1 in service
        Boolean enabled        // optional — null defaults to true
) {}
