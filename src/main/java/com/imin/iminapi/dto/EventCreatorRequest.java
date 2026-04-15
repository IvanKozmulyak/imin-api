package com.imin.iminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record EventCreatorRequest(
        @NotBlank String vibe,
        @NotBlank String tone,
        @NotBlank String genre,
        @NotBlank String city,
        @NotNull LocalDate date,
        @NotEmpty List<String> platforms
) {}
