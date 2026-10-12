package com.imin.iminapi.dto;

import java.util.List;

/**
 * The event text a poster must carry. {@code required} and {@code allowed} feed the vision text
 * gate; {@code textSentence} is the code-built first sentence of every render prompt.
 */
public record PosterTextSpec(
        List<String> required,
        List<String> allowed,
        String textSentence
) {}
