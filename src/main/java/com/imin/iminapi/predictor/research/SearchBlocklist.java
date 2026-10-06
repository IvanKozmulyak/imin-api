package com.imin.iminapi.predictor.research;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Sites a web finding may never cite: Meta properties, Spotify, ra.co, shotgun.live, cestlagreve.fr, and subdomains. */
public final class SearchBlocklist {

    static final List<String> DOMAINS = List.of("facebook.com", "fb.com", "fb.me", "instagram.com", "threads.net",
            "whatsapp.com", "messenger.com", "meta.com", "spotify.com", "ra.co", "shotgun.live", "cestlagreve.fr");

    private SearchBlocklist() {}

    /** True for a listed host, and for anything that is not a readable http(s) URL. */
    public static boolean blocked(String url) {
        String host = host(url);
        if (host == null) return true;
        for (String d : DOMAINS) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    /** The lower-case host without a trailing dot, or null when the URL is not http(s). */
    static String host(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            String host = u.getHost();
            if (host == null || host.isBlank()) return null;
            host = host.toLowerCase(Locale.ROOT);
            return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        } catch (Exception e) {
            return null;
        }
    }
}
