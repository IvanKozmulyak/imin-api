package com.imin.iminapi.marketing.model;

import java.util.Optional;

/** What a stock alert claims: a tier is nearly gone, or its price is about to go up. */
public enum StockAlertKind {
    LAST_TICKETS("last_tickets"),
    PRICE_UP("price_up");

    private final String wire;

    StockAlertKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<StockAlertKind> fromWire(String value) {
        for (StockAlertKind k : values()) {
            if (k.wire.equals(value)) return Optional.of(k);
        }
        return Optional.empty();
    }
}
