package com.imin.iminapi.dto.ai;

import com.imin.iminapi.model.ImageProvider;

import java.time.LocalDateTime;

/** Result of training a per-vibe provider style (POST /api/v1/ai/vibes/{vibeId}/train-style). */
public record VibeStyleTrainResponse(
        String vibeId,
        ImageProvider provider,
        String styleId,
        LocalDateTime trainedAt
) {}
