package com.imin.iminapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One LLM-authored poster variant. {@code heroType} replaces the old free-form {@code variant_style}:
 * the three variants of a concept carry exactly one of {@code people | object | typographic}, in that
 * order, so they are structurally distinct rather than paraphrases of each other.
 */
public record PosterVariant(
        @JsonProperty("hero_type")       String heroType,         // people | object | typographic
        @JsonProperty("ideogram_prompt") String ideogramPrompt,
        @JsonProperty("aspect_ratio")    String aspectRatio,      // always "4:5" (portrait poster)
        @JsonProperty("style_type")      String styleType         // always "Design"
) {}
