package com.imin.iminapi.dto.publicapi;

/**
 * Body of {@code POST /api/v1/public/orders/{token}/sms-consent} (§4).
 *
 * <p>{@code optIn} mirrors the unchecked-by-default checkbox. When true, {@code phone}
 * is required and validated to E.164; {@code proofText} is the verbatim checkbox label
 * stored as consent proof. When false, we record no consent (§7 — SMS is explicit only).
 * Validation is manual in {@code SmsConsentService} so we return the public-contract
 * {@code INVALID_REQUEST} envelope with a populated {@code fields} map.
 */
public record SmsConsentRequest(String phone, boolean optIn, String proofText) {}
