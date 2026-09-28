package com.imin.iminapi.audience.dto;

/**
 * {@code pending} (GET, link usable), {@code confirmed} (POST) or {@code invalid} (every failure, never told apart).
 * {@code organizerName} is null with {@code invalid}.
 */
public record ConsentConfirmationResponse(String state, String organizerName) {

    public static final String PENDING = "pending";
    public static final String CONFIRMED = "confirmed";
    public static final String INVALID = "invalid";

    public static ConsentConfirmationResponse invalid() {
        return new ConsentConfirmationResponse(INVALID, null);
    }
}
