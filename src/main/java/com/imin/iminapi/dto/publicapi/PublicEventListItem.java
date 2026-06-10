package com.imin.iminapi.dto.publicapi;

import java.time.Instant;
import java.util.UUID;

public record PublicEventListItem(
        UUID id,
        String slug,
        String name,
        String status,
        Instant publishedAt,
        String genre,
        String type,
        Instant startsAt,
        Instant endsAt,
        String timezone,
        String venueCity,
        String venueCountry,
        String posterUrl,
        String currency,
        Integer priceFromMinor,
        boolean soldOut,
        boolean lowStock,
        PublicOrganizationDto organization
) {}
