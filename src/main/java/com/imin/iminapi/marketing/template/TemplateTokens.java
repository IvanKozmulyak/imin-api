package com.imin.iminapi.marketing.template;

/**
 * The full token set that drives how a campaign email is rendered (spec §1).
 * Deliberately small: a {@link TemplatePalette}, a {@link TemplateHeader}, and a
 * CSS {@code font-family} stack. ONE renderer turns this into a 600px, fully-inlined
 * HTML shell — builtins and org-saved (AI) templates share the exact same structure.
 *
 * <p>Stored as JSON (TEXT column via {@link TemplateTokensJsonConverter}, for H2/PG
 * parity), returned on the API, and bound from the AI generator's structured output.
 * The generator's output is untrusted — {@link TemplateTokenValidator} rewrites it to a
 * known-safe instance before it is ever persisted or rendered.
 */
public record TemplateTokens(
        TemplatePalette palette,
        TemplateHeader header,
        String fontStack
) {}
