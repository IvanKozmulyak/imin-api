package com.imin.iminapi.dto.me;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * PATCH /api/v1/me/profile body. All fields are optional; null = leave unchanged.
 * Empty-string-after-trim (firstName/lastName) is rejected by the service layer
 * with a 400. {@code locale} must be one of the four supported subtags
 * (en/es/fr/uk); any other value is rejected with a 400.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfilePatchRequest(String firstName, String lastName, String locale) {}
