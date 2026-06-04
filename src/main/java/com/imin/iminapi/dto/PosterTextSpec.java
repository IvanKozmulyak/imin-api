package com.imin.iminapi.dto;

import java.util.List;

public record PosterTextSpec(
        List<String> required,
        List<String> allowed,
        String promptBlock
) {
    public String forPrompt() {
        return promptBlock;
    }
}
