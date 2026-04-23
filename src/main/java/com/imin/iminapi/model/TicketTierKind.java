package com.imin.iminapi.model;

public enum TicketTierKind {
    EARLY_BIRD, STANDARD, LATE_BIRD, CUSTOM;
    public String wireValue() {
        return switch (this) {
            case EARLY_BIRD -> "earlyBird";
            case STANDARD -> "standard";
            case LATE_BIRD -> "lateBird";
            case CUSTOM -> "custom";
        };
    }
    public static TicketTierKind fromWire(String s) {
        return switch (s) {
            case "earlyBird" -> EARLY_BIRD;
            case "standard" -> STANDARD;
            case "lateBird" -> LATE_BIRD;
            case "custom" -> CUSTOM;
            default -> throw new IllegalArgumentException("Unknown tier kind: " + s);
        };
    }
}
