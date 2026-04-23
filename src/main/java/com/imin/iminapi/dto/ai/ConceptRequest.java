package com.imin.iminapi.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConceptRequest(
        @NotBlank @Size(min = 10, max = 500) String vibe,
        String genre,
        String city,
        Integer capacity) {}
