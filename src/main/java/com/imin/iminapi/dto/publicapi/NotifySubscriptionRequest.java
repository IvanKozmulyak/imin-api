package com.imin.iminapi.dto.publicapi;

/**
 * Body of {@code POST /api/v1/public/events/{id}/notify}.
 *
 * Validation is performed manually by {@code NotifySubscriptionService} so we can return
 * the public-contract {@code INVALID_REQUEST} error envelope with a populated {@code fields}
 * map — bean-validation annotations would route through the generic {@code FIELD_INVALID}
 * handler, which the buyer FE doesn't recognise.
 *
 * @param locale optional buyer UI language (en/es/fr/uk). Normalized server-side; anything
 *               unrecognized is stored as null and the release email falls back to English.
 *               Never a validation error — a bad locale must not cost the buyer their signup.
 */
public record NotifySubscriptionRequest(String email, String locale) {}
