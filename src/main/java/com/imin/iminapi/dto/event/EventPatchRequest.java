package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Partial update body. All fields nullable; null = leave unchanged.
 * Server permits incomplete drafts and only validates on publish.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventPatchRequest(
        String name, String slug, String visibility, String genre, String type,
        Instant startsAt, Instant endsAt, String timezone, VenueDto venue,
        String description, String posterUrl, String videoUrl,
        String currency,
        Boolean squadsEnabled, Integer minSquadSize, Integer squadDiscountPct,
        Instant onSaleAt, Instant saleClosesAt,
        List<TicketTierEmbeddedPatch> tiers,
        // Whole-list semantics: when present, the server replaces all of the event's
        // promo codes with this set (safe because PATCH only operates on drafts).
        // When null, existing codes are left alone — that's what the autosave loop
        // does, since it omits this field entirely.
        List<PromoCodeEmbeddedPatch> promoCodes
) {}
