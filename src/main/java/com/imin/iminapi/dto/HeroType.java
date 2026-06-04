package com.imin.iminapi.dto;

import java.util.Locale;

/**
 * The kind of hero a poster variant is built around. Exactly one of each is sampled per
 * generation, always in this declaration order, so the three variants are structurally distinct:
 * a photographic person, a photographic object/scene, and a type-as-image poster.
 */
public enum HeroType {
    PEOPLE,
    OBJECT,
    TYPOGRAPHIC;

    /** The lowercase token used in the LLM JSON contract ("people" | "object" | "typographic"). */
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
            default -> null;
        };
    }

    /** The canonical order one-of-each is sampled / validated in. */
    public static final HeroType[] ORDER = { PEOPLE, OBJECT, TYPOGRAPHIC };
}
