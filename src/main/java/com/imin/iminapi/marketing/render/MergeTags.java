package com.imin.iminapi.marketing.render;

import java.util.regex.Matcher;
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

    private MergeTags() {}

    private static final String FIRST_NAME_TAG = "\\{\\{\\s*firstName\\s*\\}\\}";
    /** Token opening a line, with its trailing comma/colon, glued to the next word. */
    private static final Pattern LEADING_NAME = Pattern.compile(
            "(?m)^([ \\t]*)" + FIRST_NAME_TAG + "[ \\t]*[,:]?[ \\t]*(\\p{L})", Pattern.CASE_INSENSITIVE);
    /** Token opening a line, followed only by punctuation: nothing worth keeping. */
    private static final Pattern LEADING_NAME_PUNCT = Pattern.compile(
            "(?m)^([ \\t]*)" + FIRST_NAME_TAG + "[ \\t]*[,;:!?.]+[ \\t]*", Pattern.CASE_INSENSITIVE);
    /** Bold or italic markers wrapped tightly around the token, which would be left empty. */
    private static final Pattern EMPHASIZED_NAME = Pattern.compile(
            "(\\*{1,3}|_{1,3})[ \\t]*" + FIRST_NAME_TAG + "[ \\t]*\\1", Pattern.CASE_INSENSITIVE);
    /** Token with the ", " or spaces that precede it. */
    private static final Pattern TRAILING_NAME = Pattern.compile(
            "(?:,[ \\t]*|[ \\t]+)" + FIRST_NAME_TAG, Pattern.CASE_INSENSITIVE);

    /**
     * Substitute the recipient tokens in an organizer-authored string (subject or
     * markdown body). Applied BEFORE markdown/HTML rendering, so a name containing
     * HTML-special characters is still escaped by the renderer downstream.
     *
     * @param text      subject line or body markdown (null-safe → returns null)
     * @param firstName resolved first name; null, blank or email-shaped removes the token with its adjacent
     *                  punctuation ("Hi {{firstName}}," becomes "Hi,") instead of inventing a name
     * @param eventUrl  the linked-event buyer URL, or null/blank → tag removed
     */
    public static String apply(String text, String firstName, String eventUrl) {
        if (text == null || text.isEmpty()) return text;
        String name = firstName(firstName);
        String out;
        if (name != null) {
            out = FIRST_NAME.matcher(text).replaceAll(Matcher.quoteReplacement(name));
        } else {
            out = EMPHASIZED_NAME.matcher(text).replaceAll("{{firstName}}");
            out = replaceLeading(out);
            out = LEADING_NAME_PUNCT.matcher(out).replaceAll("$1");
            out = TRAILING_NAME.matcher(out).replaceAll("");
            out = FIRST_NAME.matcher(out).replaceAll("");
        }
        String url = eventUrl == null ? "" : eventUrl.trim();
        out = EVENT_URL.matcher(out).replaceAll(Matcher.quoteReplacement(url));
        return out;
    }

    private static String replaceLeading(String text) {
        Matcher m = LEADING_NAME.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String next = new String(Character.toChars(Character.toUpperCase(m.group(2).codePointAt(0))));
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + next));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Derive a greetable first name from a stored display name: the first
     * whitespace-separated token. A blank or email-shaped display name yields null:
     * we never greet someone with their email address.
     */
    public static String firstName(String displayName) {
        if (displayName == null) return null;
        String trimmed = displayName.trim();
        if (trimmed.isEmpty() || trimmed.contains("@")) return null;
        int sp = trimmed.indexOf(' ');
        String first = sp > 0 ? trimmed.substring(0, sp) : trimmed;
        return first.isEmpty() ? null : first;
    }
}
