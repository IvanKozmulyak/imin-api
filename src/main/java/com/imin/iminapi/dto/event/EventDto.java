package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.imin.iminapi.model.Event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventDto(
        UUID id, UUID orgId, String name, String slug,
        String visibility, String status, String genre, String type,
        Instant startsAt, Instant endsAt, String timezone, VenueDto venue,
        String description, String posterUrl, String videoUrl, String coverUrl,
        int sold, long revenueMinor, String currency,
        boolean squadsEnabled, int minSquadSize, int squadDiscountPct,
        Instant onSaleAt, Instant saleClosesAt,
        UUID createdBy, Instant createdAt, Instant updatedAt,
        Instant publishedAt, Instant deletedAt,
        List<TicketTierDto> tiers, List<PromoCodeDto> promoCodes, PredictionDto prediction) {

    /** Summary form used by GET /events (no tiers/promos/prediction). */
    public static EventDto summary(Event e) {
        return new EventDto(e.getId(), e.getOrgId(), e.getName(), e.getSlug(),
                e.getVisibility().wireValue(), e.getStatus().wireValue(), e.getGenre(), e.getType(),
                e.getStartsAt(), e.getEndsAt(), e.getTimezone(), venue(e),
                e.getDescription(), e.getPosterUrl(), e.getVideoUrl(), e.getCoverUrl(),
                e.getSold(), e.getRevenueMinor(), e.getCurrency(),
                e.isSquadsEnabled(), e.getMinSquadSize(), e.getSquadDiscountPct(),
                e.getOnSaleAt(), e.getSaleClosesAt(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getPublishedAt(), e.getDeletedAt(),
                null, null, null);
    }

    /** Detail form including tiers/promos/prediction (prediction may be null). */
    public static EventDto detail(Event e, List<TicketTierDto> tiers,
                                  List<PromoCodeDto> promos, PredictionDto prediction) {
        EventDto base = summary(e);
        return new EventDto(base.id, base.orgId, base.name, base.slug,
                base.visibility, base.status, base.genre, base.type,
                base.startsAt, base.endsAt, base.timezone, base.venue,
                base.description, base.posterUrl, base.videoUrl, base.coverUrl,
                base.sold, base.revenueMinor, base.currency,
                base.squadsEnabled, base.minSquadSize, base.squadDiscountPct,
                base.onSaleAt, base.saleClosesAt,
                base.createdBy, base.createdAt, base.updatedAt,
                base.publishedAt, base.deletedAt,
                tiers, promos, prediction);
    }

    private static VenueDto venue(Event e) {
        return new VenueDto(e.getVenueName(), e.getVenueStreet(), e.getVenueCity(),
                e.getVenuePostalCode(), e.getVenueCountry());
    }
}
