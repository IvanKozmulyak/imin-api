package com.imin.iminapi.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <b>Every free-text field here is bounded on purpose.</b> {@code vibe} was the only one that
 * was, and the rest are passed verbatim into the prompt the paid OpenRouter/Ideogram render is
 * built from — {@code lineup} is {@code String.join}ed straight into it. The {@code ai-concept}
 * bucket caps how OFTEN an organizer can spend that budget and {@code AiQuotaService} caps how
 * many generations a day, but both are consumed AFTER the body is bound, so neither bounds the
 * SIZE of any one call; Spring's JSON body has no cap of its own either (the 60MB multipart
 * limit is multipart-only). Without these a single authenticated MEMBER could turn one request
 * into a multi-megabyte prompt we pay for. The limits are deliberately far above any real
 * lineup or venue name — this is an abuse ceiling, not an input format.
 */
public record ConceptRequest(
        @NotBlank @Size(min = 10, max = 500) String vibe,
        @Size(max = 2000) String genre,
        @Size(max = 2000) String city,
        Integer capacity,
        // Optional selected vibe preset id (one of the VibeLibrary ids). When present it pins the
        // aesthetic; when absent the vibe is auto-suggested from genre. Validated in the service.
        @Size(max = 2000) String vibeId,
        // Optional real event details. When provided they drive the deterministic text layer:
        // the real-font Satori overlay (title/date/venue/lineup) and the QR (rsvpUrl) + address band.
        // Absent → exploratory concept (art only); present → the text layer can be composited.
        @Size(max = 2000) String title,
        LocalDate eventDate,
        @Size(max = 2000) String venue,
        @Size(max = 50) List<@Size(max = 2000) String> lineup,
        @Size(max = 2000) String address,
        @Size(max = 2000) String rsvpUrl,
        // Per-call render directive (NOT brand identity): whether to composite the org logo on
        // this generation. Resolution: request.logoOnPosters() ?? org default ?? true. Optional —
        // a stale FE that omits it falls back to the org default; never an NPE.
        Boolean logoOnPosters,
        // Optional: bind this generation to an owned event. When the event has a DJ photo, all
        // three variants render that DJ via Ideogram character reference. Cross-org → NOT_FOUND.
        UUID eventId) {}
