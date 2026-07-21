package com.imin.iminapi.marketing.template;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns UNTRUSTED token input (the AI generator's structured output) into a known-safe
 * {@link TemplateTokens} that can be inlined into a sent email (spec §5: "never trust raw
 * model output into emails").
 *
 * <p>Every colour must be a {@code #rgb}/{@code #rrggbb} hex — anything else (a CSS
 * expression, {@code url(...)}, a named colour, {@code </style>}, a data URI) is rejected,
 * so nothing but seven safe characters ever reaches an inline {@code style=} attribute.
 * The header style is whitelisted to the three known values; the font stack is clamped to a
 * safe subset of characters and length. Missing fields inherit from {@link BuiltinTemplates}
 * classic so a partial model response still yields a complete, renderable template.
 */
public final class TemplateTokenValidator {

    private TemplateTokenValidator() {}

    /** #rgb or #rrggbb only. */
    private static final Pattern HEX = Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");
    /** Font stack: letters, digits, spaces, and the handful of punctuation a CSS list needs. */
    private static final Pattern FONT_SAFE = Pattern.compile("^[A-Za-z0-9 ,'\\-]+$");
    private static final int MAX_FONT = 200;
    private static final int MAX_TITLE = 64;

    /**
     * Validate + sanitize model output. Throws {@link ApiException} 422 when a colour is
     * present but not a valid hex (a hard signal the model returned junk we must not send);
     * fills any ABSENT field from the classic builtin.
     */
    public static TemplateTokens sanitize(TemplateTokens raw) {
        TemplateTokens fallback = BuiltinTemplates.defaultTemplate().tokens();
        if (raw == null) {
            return fallback;
        }
        TemplatePalette fp = fallback.palette();
        TemplatePalette p = raw.palette();
        TemplatePalette palette = new TemplatePalette(
                color(p == null ? null : p.bg(), fp.bg()),
                color(p == null ? null : p.card(), fp.card()),
                color(p == null ? null : p.text(), fp.text()),
                color(p == null ? null : p.accent(), fp.accent()),
                color(p == null ? null : p.muted(), fp.muted()),
                color(p == null ? null : p.buttonBg(), fp.buttonBg()),
                color(p == null ? null : p.buttonText(), fp.buttonText()));

        TemplateHeader rh = raw.header();
        TemplateHeader header = new TemplateHeader(
                headerStyle(rh == null ? null : rh.style()),
                title(rh == null ? null : rh.title()));

        String fontStack = fontStack(raw.fontStack(), fallback.fontStack());
        return new TemplateTokens(palette, header, fontStack);
    }

    /** A present colour must be valid hex (else 422); an absent one falls back. */
    private static String color(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim();
        if (!HEX.matcher(v).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.FIELD_INVALID,
                    "Template colour must be a hex value like #2d5cff, got: " + v);
        }
        return v.toLowerCase(Locale.ROOT);
    }

    /** Whitelist to the three known header styles; anything else → wordmark. */
    private static String headerStyle(String style) {
        if (style == null) return TemplateHeader.WORDMARK;
        return switch (style.trim()) {
            case TemplateHeader.WORDMARK, TemplateHeader.BANNER, TemplateHeader.POSTER_HERO -> style.trim();
            default -> TemplateHeader.WORDMARK;
        };
    }

    /** Header title is optional free text — trimmed, length-clamped, or null. */
    private static String title(String title) {
        if (title == null) return null;
        String t = title.trim();
        if (t.isEmpty()) return null;
        return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) : t;
    }

    /** Clamp the font stack to a safe subset; fall back on anything unexpected. */
    private static String fontStack(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim();
        if (v.length() > MAX_FONT || !FONT_SAFE.matcher(v).matches()) return fallback;
        return v;
    }
}
