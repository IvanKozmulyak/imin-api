package com.imin.iminapi.checkoutreminder.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Published by the {@code checkout.session.expired} webhook after the seat release, inside its
 * transaction; {@link CheckoutReminderCapture} reads it after commit.
 *
 * @param created the session's creation time in epoch seconds, as Stripe reports it; null if absent
 */
public record CheckoutSessionExpired(String sessionId, Long created, String customerEmail,
                                     Map<String, String> metadata) {

    public CheckoutSessionExpired {
        Map<String, String> copy = new HashMap<>();
        if (metadata != null) metadata.forEach((k, v) -> { if (k != null && v != null) copy.put(k, v); });
        metadata = Collections.unmodifiableMap(copy);
    }
}
