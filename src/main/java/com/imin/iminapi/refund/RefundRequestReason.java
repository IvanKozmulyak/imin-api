package com.imin.iminapi.refund;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Buyer-facing reason on a refund request. Distinct from {@link RefundReason}
 * (which mirrors Stripe's enum). We keep the buyer's softer/richer choice on
 * the request row even after approval; only the Stripe call sees the mapped
 * value via {@link #toStripeReason()}.
 */
public enum RefundRequestReason {
    CANT_ATTEND,
    EVENT_CHANGED,
    DUPLICATE_PURCHASE,
    NOT_AS_DESCRIBED,
    OTHER;

    public RefundReason toStripeReason() {
        return switch (this) {
            case DUPLICATE_PURCHASE -> RefundReason.DUPLICATE;
            case OTHER -> RefundReason.OTHER;
            default -> RefundReason.REQUESTED_BY_CUSTOMER;
        };
    }

    @JsonValue
    public String toWire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static RefundRequestReason fromWire(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toLowerCase(Locale.ROOT);
        for (RefundRequestReason r : values()) {
            if (r.toWire().equals(normalized)) return r;
        }
        String accepted = Arrays.stream(values()).map(RefundRequestReason::toWire).collect(Collectors.joining(", "));
        throw new IllegalArgumentException(
            "Unknown refund-request reason '" + value + "'. Accepted: " + accepted);
    }
}
