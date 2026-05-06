package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketTierCreateRequest(
        String name,
        String kind,           // TicketTierKind wire value: "earlyBird" | "standard" | "lateBird" | "custom"
        Integer priceMinor,
        Integer quantity,
        Instant saleClosesAt,
        Integer sortOrder,     // optional — null defaults to max(existing)+1 in service
        Boolean enabled        // optional — null defaults to true
) {}
