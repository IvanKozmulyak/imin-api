package com.imin.iminapi.dto;

import java.util.List;

/**
 * Universal generation rules applied to every poster regardless of vibe (from the
 * "AI Studio Poster Style Base" draft): the aspect-ratio set to render, the universal
 * negative prompt, and the hard IP rule.
 */
public record UniversalRules(
        List<String> aspectRatios,
        String negativePrompt,
        String ipRule
) {}
