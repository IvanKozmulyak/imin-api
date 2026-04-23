package com.imin.iminapi.model;

public enum MediaKind {
    POSTER, VIDEO, COVER;

    public String wireValue() { return name().toLowerCase(); }

    public static MediaKind fromWire(String s) {
        return switch (s) {
            case "poster" -> POSTER;
            case "video" -> VIDEO;
            case "cover" -> COVER;
            default -> throw new IllegalArgumentException("Unknown media kind: " + s);
        };
    }
}
