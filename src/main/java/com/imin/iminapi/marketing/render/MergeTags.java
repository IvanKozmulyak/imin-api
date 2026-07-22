package com.imin.iminapi.marketing.render;

import java.util.regex.Pattern;

/**
 * Per-recipient personalization tokens for campaign email/SMS. The composer offers
 * {@code {{firstName}}} and {@code {{eventUrl}}} with the hint "filled per recipient
 * at send time" — this is where that fill happens (the send path was previously
 * shipping the raw tokens to recipients).
 *
 * <p>Whitespace inside the braces is tolerated ({@code {{ firstName }}}) and the tag
 * name is matched case-insensitively. {@code {{tickets_button}}} is deliberately NOT
 * handled here — it is a styled-HTML block owned by {@link CampaignEmailRenderer}.
 */
public final class MergeTags {

    private static final Pattern FIRST_NAME = Pattern.compile("\\{\\{\\s*firstName\\s*\\}\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_URL = Pattern.compile("\\{\\{\\s*eventUrl\\s*\\}\\}", Pattern.CASE_INSENSITIVE);

    /** Neutral greeting fallback when we have no usable name for the recipient. */
    public static final String FIRST_NAME_FALLBACK = "there";

    private MergeTags() {}

    /**
     * Substitute the recipient tokens in an organizer-authored string (subject or
     * markdown body). Applied BEFORE markdown/HTML rendering, so a name containing
     * HTML-special characters is still escaped by the renderer downstream.
     *
     * @param text      subject line or body markdown (null-safe → returns null)
     * @param firstName resolved first name, or null/blank → {@link #FIRST_NAME_FALLBACK}
     * @param eventUrl  the linked-event buyer URL, or null/blank → tag removed
     */
    public static String apply(String text, String firstName, String eventUrl) {
        if (text == null || text.isEmpty()) return text;
        String name = firstName(firstName);
        String out = FIRST_NAME.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(name));
        String url = eventUrl == null ? "" : eventUrl.trim();
        out = EVENT_URL.matcher(out).replaceAll(java.util.regex.Matcher.quoteReplacement(url));
        return out;
    }

    /**
     * Derive a greetable first name from a stored display name: the first
     * whitespace-separated token. An email-shaped or blank display name yields the
     * fallback — we never greet someone with their email address.
     */
    public static String firstName(String displayName) {
        if (displayName == null) return FIRST_NAME_FALLBACK;
        String trimmed = displayName.trim();
        if (trimmed.isEmpty() || trimmed.contains("@")) return FIRST_NAME_FALLBACK;
        int sp = trimmed.indexOf(' ');
        String first = sp > 0 ? trimmed.substring(0, sp) : trimmed;
        return first.isEmpty() ? FIRST_NAME_FALLBACK : first;
    }

    /** demo/self-check */
    public static void main(String[] args) {
        assert "Hey Ivan, see https://x/e/1".equals(
                apply("Hey {{firstName}}, see {{eventUrl}}", "Ivan Kozmulyak", "https://x/e/1"));
        assert "Hi there".equals(apply("Hi {{ FIRSTNAME }}", null, null));
        assert "Hi there".equals(apply("Hi {{firstName}}", "buyer@example.com", null)); // email → fallback
        assert "".equals(apply("{{eventUrl}}", null, "  "));                            // blank url → removed
        assert "Ann".equals(firstName("Ann-Marie Lee".replace("-", " ")));
        System.out.println("MergeTags OK");
    }
}
