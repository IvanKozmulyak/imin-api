package com.imin.iminapi.marketing.template;

/**
 * A template resolved to everything the renderer + the API need: its {@code key}
 * (a builtin key like {@code classic} OR a saved org-template UUID string), a display
 * {@code name}, the {@code source} ({@code builtin} | {@code ai} | {@code custom}),
 * and the {@link TemplateTokens} that drive rendering.
 *
 * <p>Builtins are code-defined ({@link BuiltinTemplates}); org templates are DB rows.
 * Both collapse to this one shape so the renderer never branches on origin.
 */
public record ResolvedTemplate(String key, String name, String source, TemplateTokens tokens) {

    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_CUSTOM = "custom";

    public boolean isBuiltin() {
        return SOURCE_BUILTIN.equals(source);
    }
}
