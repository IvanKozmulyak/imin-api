package com.imin.iminapi.marketing.template;

/**
 * The colour set of a campaign email template (spec §1). Every field is a
 * {@code #rgb}/{@code #rrggbb} hex string — validated on the way in from the AI
 * generator ({@link TemplateTokenValidator}) so untrusted model output can never
 * inject CSS/markup into a sent email.
 *
 * <ul>
 *   <li>{@code bg} — the page (outside-the-card) background</li>
 *   <li>{@code card} — the 600px sheet background</li>
 *   <li>{@code text} — body copy colour</li>
 *   <li>{@code accent} — links, header band, small flourishes</li>
 *   <li>{@code muted} — footer / secondary text</li>
 *   <li>{@code buttonBg} / {@code buttonText} — CTA colours, used both by the
 *       composer preview's sample CTA and by the {@code {{tickets_button}}}
 *       placeholder the renderer turns into a real "Get tickets" button; plain
 *       inline markdown links are still styled with {@code accent}</li>
 * </ul>
 */
public record TemplatePalette(
        String bg,
        String card,
        String text,
        String accent,
        String muted,
        String buttonBg,
        String buttonText
) {}
