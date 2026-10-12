package com.imin.iminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.List;

public record EventCreatorRequest(
        @NotBlank String vibe,
        @NotBlank String tone,
        @NotBlank String genre,
        @NotBlank String city,
        LocalDate date,                 // null when the organizer gave no date: the poster then carries none
        @NotEmpty List<String> platforms,
        String djName,
        String location,
        String title,
        String accentColor,
        String address,
        String rsvpUrl,
        String subStyleTag
) {}
