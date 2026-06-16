package com.imin.iminapi.service.analytics;

import java.util.Locale;
import java.util.Map;

/**
 * Pure, stateless mapping from a referrer host to a suggested UTM
 * {@code {source, medium, campaign}} so organizers can tag links that arrived
 * untagged. Hostnames are matched case-insensitively and with the leading
 * {@code www.} stripped. Unknown hosts fall back to
 * {@code {source: host, medium: "referral", campaign: ""}}.
 */
public final class ChannelSuggester {

    private ChannelSuggester() {}

    /** Suggested UTM tags for a referrer host. */
    public record Suggestion(String source, String medium, String campaign) {}

    /** Exact-host → {source, medium} table (campaign is always "" — channel only). */
    private static final Map<String, String[]> HOSTS = Map.ofEntries(
            Map.entry("instagram.com", new String[]{"instagram", "social"}),
            Map.entry("wa.me", new String[]{"whatsapp", "social"}),
            Map.entry("l.wa.me", new String[]{"whatsapp", "social"}),
            Map.entry("whatsapp.com", new String[]{"whatsapp", "social"}),
            Map.entry("mail.google.com", new String[]{"newsletter", "email"}),
            Map.entry("outlook.live.com", new String[]{"newsletter", "email"}),
            Map.entry("outlook.office365.com", new String[]{"newsletter", "email"}),
            Map.entry("t.co", new String[]{"twitter", "social"}),
            Map.entry("twitter.com", new String[]{"twitter", "social"}),
            Map.entry("x.com", new String[]{"twitter", "social"}),
            Map.entry("facebook.com", new String[]{"facebook", "social"}),
            Map.entry("l.facebook.com", new String[]{"facebook", "social"}));

    /**
     * Suggest a {@code {source, medium, campaign}} for the given referrer host.
     * A null/blank host falls back to {@code {"", "referral", ""}}.
     */
    public static Suggestion suggest(String referrerHost) {
        String host = normalize(referrerHost);
        String[] hit = HOSTS.get(host);
        if (hit != null) return new Suggestion(hit[0], hit[1], "");
        return new Suggestion(host, "referral", ""); // fallback: source = host
    }

    private static String normalize(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }
}
