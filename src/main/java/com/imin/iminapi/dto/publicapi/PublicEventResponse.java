package com.imin.iminapi.dto.publicapi;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;

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
        String posterUrl,
        String videoUrl,
        String currency,
        Instant onSaleAt,
        Instant saleClosesAt,
        PublicOrganizationDto organization,
        List<PublicTierDto> tiers,
        String metaPixelId
) {
    public static PublicEventResponse from(Event e, Organization org, List<PublicTierDto> tiers, String metaPixelId) {
        return new PublicEventResponse(
                e.getId(),
                e.getSlug(),
                e.getName(),
                e.getStatus().wireValue(),
                e.getPublishedAt(),
                e.getGenre(),
                e.getType(),
                e.getDescription(),
                e.getStartsAt(),
                e.getEndsAt(),
                e.getTimezone(),
                new PublicVenueDto(
                        e.getVenueName(),
                        e.getVenueStreet(),
                        e.getVenueCity(),
                        e.getVenuePostalCode(),
                        e.getVenueCountry(),
                        e.getVenueLatitude(),
                        e.getVenueLongitude()),
                e.getPosterUrl(),
                e.getVideoUrl(),
                e.getCurrency(),
                e.getOnSaleAt(),
                e.getSaleClosesAt(),
                new PublicOrganizationDto(org.getName(), org.getSlug()),
                tiers,
                metaPixelId);
    }
}
