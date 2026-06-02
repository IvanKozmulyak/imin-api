package com.imin.iminapi.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConceptRequest(
        @NotBlank @Size(min = 10, max = 500) String vibe,
        String genre,
        String city,
        Integer capacity,
        // Optional selected vibe preset id (one of the VibeLibrary ids). When present it pins the
        // aesthetic; when absent the vibe is auto-suggested from genre. Validated in the service.
        String vibeId) {}
