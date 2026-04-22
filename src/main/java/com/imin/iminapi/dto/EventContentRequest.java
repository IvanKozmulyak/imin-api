package com.imin.iminapi.dto;

import jakarta.validation.constraints.NotBlank;

public record EventContentRequest(
        @NotBlank String prompt
) {}
