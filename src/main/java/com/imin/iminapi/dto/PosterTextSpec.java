package com.imin.iminapi.dto;

import java.util.List;

/**
 * The event text a poster must carry. {@code required} and {@code allowed} feed the vision text
 * gate; {@code textSentence} is the code-built first sentence of every render prompt.
 * {@code exact} lists the strings the gate accepts only as written or in ALL CAPS (date, city).
 */
public record PosterTextSpec(
        List<String> required,
        List<String> allowed,
        String textSentence,
        List<String> exact
) {
    public PosterTextSpec(List<String> required, List<String> allowed, String textSentence) {
        this(required, allowed, textSentence, List.of());
    }
}
