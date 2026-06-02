package com.imin.iminapi.dto.ai;

import java.util.List;

/** Catalog entry for the AI Studio vibe picker. {@code palette} doubles as the swatch. */
public record VibeSummary(
        String id,
        String name,
        List<String> genres,
        List<String> palette,
        boolean textOnly
) {}
