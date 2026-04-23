package com.imin.iminapi.model;

public enum EventStatus {
    DRAFT, LIVE, PAST, CANCELLED;
    public String wireValue() { return name().toLowerCase(); }
    public static EventStatus fromWire(String s) { return valueOf(s.toUpperCase()); }
}
