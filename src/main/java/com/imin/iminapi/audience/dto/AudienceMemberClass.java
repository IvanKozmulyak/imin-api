package com.imin.iminapi.audience.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Guest class from fan_features (paid orders only); replaces the lifecycle stages in the Audience tab. */
@Schema(name = "AudienceMemberClass", enumAsRef = true)
public enum AudienceMemberClass {
    LOYAL, REPEAT, FIRST_TIMER, LAPSING, DORMANT, IMPORTED, NONE;

    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static AudienceMemberClass fromKey(String key) {
        return parse(key).orElseThrow(() -> new IllegalArgumentException("unknown guest class: " + key));
    }

    /** Empty for null or an unknown key. */
    public static Optional<AudienceMemberClass> parse(String key) {
        if (key == null) return Optional.empty();
        return Arrays.stream(values()).filter(c -> c.key().equals(key)).findFirst();
    }
}
