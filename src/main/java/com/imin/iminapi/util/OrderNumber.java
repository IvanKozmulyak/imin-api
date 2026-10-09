package com.imin.iminapi.util;

import java.util.UUID;

/** The one human reference for an order: first 8 chars of its UUID, as the orders API and CSV print it. */
public final class OrderNumber {

    private OrderNumber() {}

    /** The 8-char code, e.g. {@code 3f9a1c2e}. */
    public static String code(UUID orderId) {
        return orderId.toString().substring(0, 8);
    }

    /** The printed form, e.g. {@code #3f9a1c2e}. */
    public static String display(UUID orderId) {
        return "#" + code(orderId);
    }
}
