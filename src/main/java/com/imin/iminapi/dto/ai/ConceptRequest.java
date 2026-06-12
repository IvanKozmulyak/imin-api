package com.imin.iminapi.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ConceptRequest(
        @NotBlank @Size(min = 10, max = 500) String vibe,
        String genre,
        String city,
        Integer capacity,
        // Optional selected vibe preset id (one of the VibeLibrary ids). When present it pins the
        // aesthetic; when absent the vibe is auto-suggested from genre. Validated in the service.
        String vibeId,
        // Optional real event details. When provided they drive the deterministic text layer:
        // the real-font Satori overlay (title/date/venue/lineup) and the QR (rsvpUrl) + address band.
        // Absent → exploratory concept (art only); present → the text layer can be composited.
        String title,
        LocalDate eventDate,
        String venue,
        List<String> lineup,
        String address,
        String rsvpUrl,
        // Per-call render directive (NOT brand identity): whether to composite the org logo on
        // this generation. Resolution: request.logoOnPosters() ?? org default ?? true. Optional —
        // a stale FE that omits it falls back to the org default; never an NPE.
        Boolean logoOnPosters,
        // Optional: bind this generation to an owned event. When the event has a DJ photo, all
        // three variants render that DJ via Ideogram character reference. Cross-org → NOT_FOUND.
        UUID eventId) {}
