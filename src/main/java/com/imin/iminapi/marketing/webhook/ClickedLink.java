package com.imin.iminapi.marketing.webhook;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

/** Which link a Resend click hit: TICKET (the campaign event's buyer page or below), UNSUBSCRIBE (the API unsubscribe path
 *  on any host, or the buyer site's unsubscribe page), OTHER, or UNKNOWN (no link, or no absolute URL with a host).
 *  Hosts match in any case; never throws. */
public enum ClickedLink {
    TICKET, UNSUBSCRIBE, OTHER, UNKNOWN;

    static final String UNSUBSCRIBE_PATH = "/api/v1/public/unsubscribe";
    static final String UNSUBSCRIBE_PAGE_PATH = "/unsubscribe";

    public static ClickedLink classify(String link, UUID campaignEventId, String buyerSiteBaseUrl,
                                       String apiPublicBaseUrl) {
        if (link == null || link.isBlank()) return UNKNOWN;
        URI uri;
        try {
            uri = new URI(link.trim());
        } catch (Exception e) {
            return UNKNOWN;
        }
        try {
            String host = lower(uri.getHost());
            if (host == null) return UNKNOWN;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            // Any host: older emails may carry a different API base host.
            if (isAtOrBelow(path, UNSUBSCRIBE_PATH)) return UNSUBSCRIBE;
            Base buyer = Base.of(buyerSiteBaseUrl);
            // The footer link opens the buyer site's unsubscribe page.
            if (host.equals(buyer.host()) && isAtOrBelow(path, buyer.path() + UNSUBSCRIBE_PAGE_PATH)) return UNSUBSCRIBE;
            if (campaignEventId != null && host.equals(buyer.host())
                    && isAtOrBelow(path, buyer.path() + "/e/" + campaignEventId)) {
                return TICKET;
            }
            return OTHER;
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    /** {@code path} is {@code prefix} itself or a sub-path of it, so {@code /e/{id}x} is not {@code /e/{id}}. */
    private static boolean isAtOrBelow(String path, String prefix) {
        String p = path.toLowerCase(Locale.ROOT);
        String pre = prefix.toLowerCase(Locale.ROOT);
        return p.equals(pre) || p.startsWith(pre + "/");
    }

    private static String lower(String s) {
        return s == null || s.isBlank() ? null : s.toLowerCase(Locale.ROOT);
    }

    /** A configured base URL's host and path, the path without a trailing slash; both empty when it does not parse. */
    private record Base(String host, String path) {
        static Base of(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) return new Base("", "");
            try {
                URI u = new URI(baseUrl.trim());
                String host = lower(u.getHost());
                String path = u.getRawPath() == null ? "" : u.getRawPath();
                while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                return new Base(host == null ? "" : host, path);
            } catch (Exception e) {
                return new Base("", "");
            }
        }
    }
}
