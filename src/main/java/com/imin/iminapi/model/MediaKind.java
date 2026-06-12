package com.imin.iminapi.model;

public enum MediaKind {
    POSTER("poster"), VIDEO("video"), DJ_PHOTO("dj-photo");

    private final String wire;

    MediaKind(String wire) { this.wire = wire; }

    public String wireValue() { return wire; }

    public static MediaKind fromWire(String s) {
        return switch (s) {
            case "poster" -> POSTER;
            case "video" -> VIDEO;
            case "dj-photo" -> DJ_PHOTO;
            default -> throw new IllegalArgumentException("Unknown media kind: " + s);
        };
    }
}
