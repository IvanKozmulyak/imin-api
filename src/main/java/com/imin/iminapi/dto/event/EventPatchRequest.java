package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        Instant onSaleAt, Instant saleClosesAt,
        List<TicketTierEmbeddedPatch> tiers,
        // Whole-list semantics: when present, the server replaces all of the event's
        // promo codes with this set (safe because PATCH only operates on drafts).
        // When null, existing codes are left alone — that's what the autosave loop
        // does, since it omits this field entirely.
        List<PromoCodeEmbeddedPatch> promoCodes,
        // AI-provenance signal (V71), honoured on CREATE only: the id of the AI-studio
        // Concept this draft was promoted from. When present and owned by the caller's
        // org, the server stamps events.concept_ai_generated = true. Absent ≠ manual —
        // absence leaves provenance NULL (unknown); see V71__event_ai_provenance.sql.
        UUID sourceConceptId
) {
    /** Back-compat 17-arg constructor (pre-V71 shape) — provenance signal absent (NULL, unknown). */
    public EventPatchRequest(String name, String slug, String visibility, String genre, String type,
                             Instant startsAt, Instant endsAt, String timezone, VenueDto venue,
                             String description, String posterUrl, String videoUrl, String currency,
                             Instant onSaleAt, Instant saleClosesAt,
                             List<TicketTierEmbeddedPatch> tiers, List<PromoCodeEmbeddedPatch> promoCodes) {
        this(name, slug, visibility, genre, type, startsAt, endsAt, timezone, venue, description,
                posterUrl, videoUrl, currency, onSaleAt, saleClosesAt, tiers, promoCodes, null);
    }
}
