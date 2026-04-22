package com.imin.iminapi.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GeneratedPoster(
        UUID id,
        String variantStyle,
        String rawUrl,
        String finalUrl,
        long seed,
        String ideogramPromptUsed,
        List<String> referenceImagesUsed,
        Map<String, Object> overlaysApplied,
        String status,
        String failureReason
) {}
