package com.imin.iminapi.marketing.template;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The four code-defined campaign email templates (spec §3). No DB rows — they exist
 * as constants so every org gets them for free and they can never be deleted.
 *
 * <ul>
 *   <li>{@code classic} — light, brand-adjacent (imin's {@code #f4f2fa} page /
 *       {@code #2d5cff} accent), a wordmark header.</li>
 *   <li>{@code midnight} — dark card with light text; colours are set EXPLICITLY and
 *       inline so Gmail/Outlook dark-mode inversion leaves it readable (the accent is a
 *       lightened blue that keeps contrast on the dark card).</li>
 *   <li>{@code poster} — the linked event's poster as a full-bleed hero; degrades to a
 *       {@code banner} header when the campaign has no poster.</li>
 *   <li>{@code mono} — minimal, near-monochrome with a Space Mono stack.</li>
 * </ul>
 *
 * <p>{@link #DEFAULT_KEY} ({@code classic}) is the fallback for a campaign whose
 * {@code template_key} is null/unknown, so legacy rows and bad input still render.
 */
public final class BuiltinTemplates {

    private BuiltinTemplates() {}

    public static final String DEFAULT_KEY = "classic";

    /** DM Sans first (matches the transactional templates), then robust system fallbacks. */
    private static final String SANS =
            "'DM Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
    private static final String MONO =
            "'Space Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

    private static final ResolvedTemplate CLASSIC = new ResolvedTemplate(
            "classic", "Classic", ResolvedTemplate.SOURCE_BUILTIN,
            new TemplateTokens(
                    new TemplatePalette("#f4f2fa", "#ffffff", "#11091f", "#2d5cff",
                            "#6b6480", "#2d5cff", "#ffffff"),
                    new TemplateHeader(TemplateHeader.WORDMARK, null),
                    SANS));

    private static final ResolvedTemplate MIDNIGHT = new ResolvedTemplate(
            "midnight", "Midnight", ResolvedTemplate.SOURCE_BUILTIN,
            new TemplateTokens(
                    new TemplatePalette("#0f0a1f", "#1a1230", "#f4f2fa", "#8b9bff",
                            "#a89fc8", "#6b7bff", "#0f0a1f"),
                    new TemplateHeader(TemplateHeader.BANNER, null),
                    SANS));

    private static final ResolvedTemplate POSTER = new ResolvedTemplate(
            "poster", "Poster", ResolvedTemplate.SOURCE_BUILTIN,
            new TemplateTokens(
                    new TemplatePalette("#f4f2fa", "#ffffff", "#11091f", "#2d5cff",
                            "#6b6480", "#2d5cff", "#ffffff"),
                    new TemplateHeader(TemplateHeader.POSTER_HERO, null),
                    SANS));

    private static final ResolvedTemplate MONO_TPL = new ResolvedTemplate(
            "mono", "Mono", ResolvedTemplate.SOURCE_BUILTIN,
            new TemplateTokens(
                    new TemplatePalette("#ffffff", "#ffffff", "#11091f", "#11091f",
                            "#6b6480", "#11091f", "#ffffff"),
                    new TemplateHeader(TemplateHeader.WORDMARK, null),
                    MONO));

    /** Insertion order is the order the composer's picker shows them. */
    private static final Map<String, ResolvedTemplate> BY_KEY = new java.util.LinkedHashMap<>();
    static {
        for (ResolvedTemplate t : List.of(CLASSIC, MIDNIGHT, POSTER, MONO_TPL)) {
            BY_KEY.put(t.key(), t);
        }
    }

    /** The builtins in display order. */
    public static List<ResolvedTemplate> all() {
        return List.copyOf(BY_KEY.values());
    }

    /** True when {@code key} names a builtin (not a UUID org-template id). */
    public static boolean isBuiltinKey(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    /** The builtin for {@code key}, or null when it is not a builtin key. */
    public static ResolvedTemplate byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** The default builtin ({@code classic}) — never null. */
    public static ResolvedTemplate defaultTemplate() {
        return CLASSIC;
    }

    /** The set of builtin keys, for validation. */
    public static java.util.Set<String> keys() {
        return BY_KEY.keySet().stream().collect(Collectors.toUnmodifiableSet());
    }
}
