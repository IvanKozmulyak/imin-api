package com.imin.iminapi.marketing.template;

/**
 * The header treatment of a campaign email template (spec §1).
 *
 * <ul>
 *   <li>{@code style} — one of {@code wordmark} | {@code banner} | {@code posterHero}.
 *       {@code posterHero} shows the linked event's poster as a full-bleed hero and
 *       degrades to {@code banner} when the campaign has no poster
 *       ({@link BuiltinTemplates}/renderer handle the fallback).</li>
 *   <li>{@code title} — optional header text. When null the renderer falls back to the
 *       org's brand/display name, so the ORGANIZER identity leads (imin is the platform,
 *       not the star).</li>
 * </ul>
 */
public record TemplateHeader(String style, String title) {

    public static final String WORDMARK = "wordmark";
    public static final String BANNER = "banner";
    public static final String POSTER_HERO = "posterHero";
}
