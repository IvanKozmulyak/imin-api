package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Partial update body. All fields nullable; null = leave unchanged.
 * Server permits incomplete drafts and only validates on publish.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventPatchRequest(
        String name, String slug, String visibility, String genre, String type,
        Instant startsAt, Instant endsAt, String timezone, VenueDto venue,
        String description, String posterUrl, String videoUrl, String coverUrl,
        String currency,
        Boolean squadsEnabled, Integer minSquadSize, Integer squadDiscountPct,
        Instant onSaleAt, Instant saleClosesAt
) {}
