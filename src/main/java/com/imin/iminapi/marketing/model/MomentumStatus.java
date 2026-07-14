package com.imin.iminapi.marketing.model;

/** Suggestion lifecycle (spec §6.2). 'suggested' is the only live state. */
public enum MomentumStatus {
    SUGGESTED, APPROVED, DISMISSED, EXPIRED;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static MomentumStatus fromWire(String s) {
        return valueOf(s.toUpperCase());
    }
}
