package com.imin.iminapi.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Raw LLM output for the 3-concept generation. Mapped to ConceptCardDto by the service. */
public record ConceptSet(List<LlmConcept> concepts) {

    public record LlmConcept(
            String name,
            String description,
            LlmCaptions captions,
            @JsonProperty("suggested_genre") String suggestedGenre,
            @JsonProperty("suggested_type") String suggestedType,
            @JsonProperty("suggested_capacity") Integer suggestedCapacity) {}

    public record LlmCaptions(String instagram, String tiktok, String x) {}
}
