package com.imin.iminapi.dto.gate;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for {@code POST /api/v1/gate/login}. The "username" the staff enter on
 * the gate device is the org's existing {@code slug} (e.g. "acme-events"),
 * not an email or a separately-issued identifier.
 */
public record GateLoginRequest(@NotBlank String orgSlug, @NotBlank String password) {}
