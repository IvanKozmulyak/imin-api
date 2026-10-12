package com.imin.iminapi.service.poster;

/**
 * Assembles the prompt Ideogram renders: the code-built text sentence first (late text is what the
 * renderer drops), then the art director's visual, then the lettering style, then the clothing rule.
 */
public final class RenderPrompt {

    static final String CLOTHING_CLAUSE = "Every person shown is fully clothed.";

    private RenderPrompt() {}

    public static String compose(String textSentence, String visual, String typeStyle) {
        StringBuilder p = new StringBuilder(textSentence.trim());
        if (visual != null && !visual.isBlank()) {
            p.append(' ').append(visual.trim());
        }
        if (typeStyle != null && !typeStyle.isBlank()) {
            String style = typeStyle.trim();
            while (style.endsWith(".")) style = style.substring(0, style.length() - 1).trim();
            p.append(" Type: ").append(style).append('.');
        }
        return p.append(' ').append(CLOTHING_CLAUSE).toString();
    }
}
