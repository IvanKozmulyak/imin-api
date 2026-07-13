package com.imin.iminapi.dto.publicapi;

/**
 * Response for {@code POST /api/v1/public/orders/{token}/sms-consent}.
 * {@code saved} is true only when an explicit SMS consent proof was recorded.
 */
public record SmsConsentResponse(boolean saved) {
    // Factories are named ok()/declined() rather than saved()/notSaved(): a record
    // component `saved` already generates a boolean `saved()` accessor, so a static
    // `saved()` factory would clash with it (javac rejects the name collision).
    public static SmsConsentResponse ok() { return new SmsConsentResponse(true); }
    public static SmsConsentResponse declined() { return new SmsConsentResponse(false); }
}
