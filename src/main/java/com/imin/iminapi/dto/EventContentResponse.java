package com.imin.iminapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

public record EventContentResponse(
        List<String> names,
        List<String> taglines,
        String genre,
        String tone,
        String vibe,
        @JsonProperty("description_long")  String descriptionLong,
        @JsonProperty("description_short") String descriptionShort,
        @JsonProperty("instagram_caption") String instagramCaption,
        @JsonProperty("story_text")        String storyText,
        @JsonProperty("facebook_post")     String facebookPost,
        @JsonProperty("x_post")            String xPost,
        @JsonProperty("pricing_suggestion") PricingSuggestion pricingSuggestion,
        @JsonProperty("optimal_timing")    String optimalTiming,
        @JsonProperty("color_palette")     List<String> colorPalette
) {
    public record PricingSuggestion(
            BigDecimal floor,
            BigDecimal ceiling,
            String reasoning
    ) {}
}
