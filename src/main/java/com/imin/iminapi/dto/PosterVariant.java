package com.imin.iminapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PosterVariant(
        @JsonProperty("variant_style")   String variantStyle,     // atmospheric | graphic | minimal
        @JsonProperty("ideogram_prompt") String ideogramPrompt,
        @JsonProperty("aspect_ratio")    String aspectRatio,      // "3:4" | "1:1" | "4:5"
        @JsonProperty("style_type")      String styleType         // always "Design" in Phase 1
) {}
