package com.imin.iminapi.refund;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public enum RefundReason {
    REQUESTED_BY_CUSTOMER, DUPLICATE, FRAUDULENT, OTHER;

    /**
     * The lowercase string Stripe accepts on RefundCreateParams.reason.
     * OTHER maps to null because Stripe's enum does not include it — we just
     * omit the field and Stripe records the refund with no reason set.
     */
    public String toStripe() {
        return switch (this) {
            case REQUESTED_BY_CUSTOMER -> "requested_by_customer";
            case DUPLICATE -> "duplicate";
            case FRAUDULENT -> "fraudulent";
            case OTHER -> null;
        };
    }

    /**
     * Stable wire format used in BOTH API request and response bodies (lowercase
     * enum name). The {@code @JsonValue} makes responses serialize as lowercase;
     * {@code fromWire} (annotated {@code @JsonCreator}) makes requests accept the
     * same lowercase form. Frontend code can use a single string format everywhere.
     */
    @JsonValue
    public String toWire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static RefundReason fromWire(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toLowerCase(Locale.ROOT);
        for (RefundReason r : values()) {
            if (r.toWire().equals(normalized)) return r;
        }
        String accepted = Arrays.stream(values()).map(RefundReason::toWire).collect(Collectors.joining(", "));
        throw new IllegalArgumentException(
            "Unknown refund reason '" + value + "'. Accepted: " + accepted);
    }
}
