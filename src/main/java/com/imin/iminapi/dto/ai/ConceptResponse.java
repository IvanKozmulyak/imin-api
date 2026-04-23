package com.imin.iminapi.dto.ai;

import java.util.List;
import java.util.UUID;

public record ConceptResponse(
        UUID conceptId,
        String name,
        String description,
        List<PosterDto> posters,
        List<String> palette,
        List<SuggestedTierDto> suggestedTiers,
        Integer suggestedCapacity,
        Integer confidencePct) {}
