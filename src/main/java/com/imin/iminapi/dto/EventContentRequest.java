package com.imin.iminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EventContentRequest(
        // POST /events/ai-content is unauthenticated and every call spends an LLM budget imin pays
        // for. The ai-content bucket caps how often an anonymous caller can bill us; this caps how
        // much each of those calls can carry. 2000 is far above any real event brief.
        @NotBlank @Size(max = 2000) String prompt
) {}
