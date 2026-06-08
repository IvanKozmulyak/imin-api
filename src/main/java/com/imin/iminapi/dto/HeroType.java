package com.imin.iminapi.dto;

import java.util.Locale;

/**
 * The kind of hero a poster variant is built around. The three original modes (PEOPLE, OBJECT,
 * TYPOGRAPHIC) remain the legacy default plan order; SCENE and ABSTRACT_GRAPHIC are new per-vibe
 * variant plan modes.
 */
public enum HeroType {
    PEOPLE,
    OBJECT,
    TYPOGRAPHIC,
    SCENE,
    ABSTRACT_GRAPHIC;

    /** The lowercase token used in the LLM JSON contract. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse the LLM JSON token; null/unknown → null so callers can validate. */
    public static HeroType fromWire(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "people" -> PEOPLE;
            case "object" -> OBJECT;
            case "typographic" -> TYPOGRAPHIC;
            case "scene" -> SCENE;
            case "abstract_graphic" -> ABSTRACT_GRAPHIC;
            default -> null;
        };
    }

    /** Legacy default plan order, used only as a fallback when a vibe declares no variant_plan. */
    public static final HeroType[] ORDER = { PEOPLE, OBJECT, TYPOGRAPHIC };
}
