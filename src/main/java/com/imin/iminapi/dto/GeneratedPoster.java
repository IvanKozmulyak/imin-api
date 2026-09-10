package com.imin.iminapi.dto;

import java.util.List;
import java.util.UUID;

public record GeneratedPoster(
        UUID id,
        String variantStyle,
        String rawUrl,
        String finalUrl,
        long seed,
        List<String> referenceImagesUsed,
        String status,
        String failureReason
) {}
