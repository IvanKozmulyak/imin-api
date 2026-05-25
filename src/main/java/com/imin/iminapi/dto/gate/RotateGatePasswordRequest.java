package com.imin.iminapi.dto.gate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/orgs/{orgId}/gate/credentials/rotate}.
 * Minimum length 12 — gate phones share this password across staff and live
 * at venues; brute-forcing the door is a real threat so we set the floor
 * higher than the user-side default.
 */
public record RotateGatePasswordRequest(@NotBlank @Size(min = 12, max = 256) String password) {}
