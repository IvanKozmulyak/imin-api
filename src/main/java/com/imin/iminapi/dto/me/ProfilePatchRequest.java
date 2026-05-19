package com.imin.iminapi.dto.me;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * PATCH /api/v1/me/profile body. Both fields are optional; null = leave unchanged.
 * Empty-string-after-trim is rejected by the service layer with a 400.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfilePatchRequest(String firstName, String lastName) {}
