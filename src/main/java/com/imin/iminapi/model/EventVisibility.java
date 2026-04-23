package com.imin.iminapi.model;

public enum EventVisibility {
    PUBLIC, PRIVATE;
    public String wireValue() { return name().toLowerCase(); }
    public static EventVisibility fromWire(String s) { return valueOf(s.toUpperCase()); }
}
