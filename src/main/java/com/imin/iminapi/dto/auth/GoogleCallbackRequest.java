package com.imin.iminapi.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /auth/google/callback} — the code + signed state from Google's redirect. */
public record GoogleCallbackRequest(
        @NotBlank String code,
        @NotBlank String state
) {}
