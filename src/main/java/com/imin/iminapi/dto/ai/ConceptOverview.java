package com.imin.iminapi.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ConceptOverview(
        String name,
        String description,
        @JsonProperty("palette_hexes") List<String> paletteHexes,
        @JsonProperty("suggested_capacity") Integer suggestedCapacity,
        @JsonProperty("confidence_pct") Integer confidencePct) {}
