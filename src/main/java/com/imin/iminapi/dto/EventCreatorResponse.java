package com.imin.iminapi.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record EventCreatorResponse(
        UUID id,
        String status,
        UUID posterGenerationId,
        String subStyleTag,
        List<GeneratedPoster> posters,
        LocalDateTime createdAt
) {}
