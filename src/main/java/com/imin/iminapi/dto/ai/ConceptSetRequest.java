package com.imin.iminapi.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Bounded for the reason {@link ConceptRequest} spells out: these strings reach a paid LLM prompt. */
public record ConceptSetRequest(
        @NotBlank @Size(min = 10, max = 500) String vibe,
        @Size(max = 2000) String genre,
        @Size(max = 2000) String city,
        Integer capacity) {}
