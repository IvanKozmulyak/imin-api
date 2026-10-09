package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.imin.iminapi.model.Event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventDto(
        UUID id, UUID orgId, String name, String slug,
        String visibility, String status, String genre, String subGenre, String type,
        Instant startsAt, Instant endsAt, String timezone, VenueDto venue,
        String description, String posterUrl, String videoUrl, String djPhotoUrl,
        int sold, Integer capacity, long revenueMinor, String currency,
        Instant onSaleAt, Instant saleClosesAt,
        UUID createdBy, Instant createdAt, Instant updatedAt,
        Instant publishedAt, Instant deletedAt,
        List<TicketTierDto> tiers, List<PromoCodeDto> promoCodes, PredictionDto prediction,
        Boolean almostGone, Integer ticketsLeft) {

    /**
     * Summary form used by GET /events (no tiers/promos/prediction). Sales figures come from
     * live totals; the event's own sold/revenue columns are never written.
     */
    public static EventDto summary(Event e, EventSalesFigures f) {
        return build(e, f, null, null);
    }

    /**
     * Summary with the almost-gone fields: {@code ticketsLeft} is the scarcest almost-gone tier's remaining
     * count (null when no tier is almost gone or the event is not on sale), not a total across tiers.
     */
    public static EventDto summaryWithAlmostGone(Event e, EventSalesFigures f, Integer ticketsLeft) {
        return build(e, f, ticketsLeft != null, ticketsLeft);
    }

    private static EventDto build(Event e, EventSalesFigures f, Boolean almostGone, Integer ticketsLeft) {
        return new EventDto(e.getId(), e.getOrgId(), e.getName(), e.getSlug(),
                e.getVisibility().wireValue(), e.getStatus().wireValue(), e.getGenre(), e.getSubGenre(), e.getType(),
                e.getStartsAt(), e.getEndsAt(), e.getTimezone(), venue(e),
                e.getDescription(), e.getPosterUrl(), e.getVideoUrl(), e.getDjPhotoUrl(),
                f.sold(), f.capacity(), f.revenueMinor(), e.getCurrency(),
                e.getOnSaleAt(), e.getSaleClosesAt(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getPublishedAt(), e.getDeletedAt(),
                null, null, null,
                almostGone, ticketsLeft);
    }

    /** Detail form including tiers/promos/prediction (prediction may be null). */
    public static EventDto detail(Event e, EventSalesFigures f, List<TicketTierDto> tiers,
                                  List<PromoCodeDto> promos, PredictionDto prediction) {
        EventDto base = summary(e, f);
        return new EventDto(base.id, base.orgId, base.name, base.slug,
                base.visibility, base.status, base.genre, base.subGenre, base.type,
                base.startsAt, base.endsAt, base.timezone, base.venue,
                base.description, base.posterUrl, base.videoUrl, base.djPhotoUrl,
                base.sold, base.capacity, base.revenueMinor, base.currency,
                base.onSaleAt, base.saleClosesAt,
                base.createdBy, base.createdAt, base.updatedAt,
                base.publishedAt, base.deletedAt,
                tiers, promos, prediction, null, null);
    }

    private static VenueDto venue(Event e) {
        return new VenueDto(e.getVenueName(), e.getVenueStreet(), e.getVenueCity(),
                e.getVenuePostalCode(), e.getVenueCountry());
    }
}
