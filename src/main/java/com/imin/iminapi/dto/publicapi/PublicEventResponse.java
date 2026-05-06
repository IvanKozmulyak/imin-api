package com.imin.iminapi.dto.publicapi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicEventResponse(
        UUID id,
        String slug,
        String name,
        String status,         // wireValue() — "live" | "past" | "cancelled"
        Instant publishedAt,
        String genre,
        String type,
        String description,
        Instant startsAt,
        Instant endsAt,
        String timezone,
        PublicVenueDto venue,
        String coverUrl,
        String posterUrl,
        String videoUrl,
        String currency,
        Instant onSaleAt,
        Instant saleClosesAt,
        boolean squadsEnabled,
        int minSquadSize,
        int squadDiscountPct,
        PublicOrganizationDto organization,
        List<PublicTierDto> tiers
) {}
