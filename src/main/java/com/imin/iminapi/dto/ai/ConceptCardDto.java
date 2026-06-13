package com.imin.iminapi.dto.ai;

import java.util.List;
import java.util.UUID;

public record ConceptCardDto(
        UUID conceptId,
        String name,
        String description,
        CaptionsDto captions,
        String suggestedGenre,
        String suggestedType,
        Integer suggestedCapacity,
        List<SuggestedTierDto> suggestedTiers) {}
