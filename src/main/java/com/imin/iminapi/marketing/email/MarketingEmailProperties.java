package com.imin.iminapi.marketing.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Separate sending identity for bulk marketing email (spec §3). Bulk sends go
 * from a marketing subdomain (e.g. news.imin.wtf) with its own SPF/DKIM so they
 * never touch the transactional (auth-email) reputation carried by imin.email.*.
 */
@ConfigurationProperties(prefix = "imin.marketing")
public class MarketingEmailProperties {
    private String fromAddress = "";
    /** Used only when no organizer name is known; the yaml default matches. */
    private String fromName = "IMIN";
    /**
     * The buyer site root. Used as the {@code {{eventUrl}}} fallback when a
     * campaign has no linked event.
     *
     * <p><b>No longer builds the unsubscribe link</b> — see {@link #unsubscribeUrl}.
     * It did until 2026-08-16, as {@code baseUrl + "/optout?token=" + token},
     * and that URL 404'd in production: {@code imin-public} has never served
     * {@code /optout}. Every marketing email since the Momentum Engine went live
     * carried a dead unsubscribe link in both its footer and its
     * {@code List-Unsubscribe} header.
     */
    private String buyerSiteBaseUrl = "https://app.imin.wtf";

    /**
     * The API's own public base, no trailing slash — where the unsubscribe
     * endpoint actually lives.
     *
     * <p>Deliberately a different host from {@link #buyerSiteBaseUrl}. The
     * opt-out is served by {@code PublicUnsubscribeController}, which owns the
     * consent write and is the sole suppression authority; routing recipients
     * through the buyer site would put a second hop in front of a legally
     * load-bearing action for no gain.
     *
     * <p><b>Defaults to production, not localhost</b>, for the same reason
     * {@link #buyerSiteBaseUrl} does (2026-07-22): an unset env var must never
     * put a {@code http://localhost:8080} link in a buyer's inbox.
     * {@code imin.ticket.api-public-base-url} — the sibling used for emailed QR
     * and pkpass links — reads the same {@code IMIN_API_PUBLIC_BASE_URL} and
     * carried the localhost default until 2026-08-16; it now matches this one,
     * so the shared env var can no longer mean two different things.
     */
    private String apiPublicBaseUrl = "https://api.imin.wtf";

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getBuyerSiteBaseUrl() { return buyerSiteBaseUrl; }
    public void setBuyerSiteBaseUrl(String v) { this.buyerSiteBaseUrl = v; }
    public String getApiPublicBaseUrl() { return apiPublicBaseUrl; }
    public void setApiPublicBaseUrl(String v) { this.apiPublicBaseUrl = v; }

    /**
     * The one place an unsubscribe URL is built.
     *
     * <p>The same URL goes in the footer link and in the {@code List-Unsubscribe}
     * header, and {@code PublicUnsubscribeController} serves both verbs on it:
     * {@code POST} is RFC 8058 one-click (the button Gmail and Apple Mail render
     * themselves), {@code GET} is the human confirmation page. Two call sites
     * concatenated this by hand before, which is how they stayed wrong together.
     *
     * <p>The token is a signed base64url string and carries no path-reserved
     * characters, so it is safe as a path segment — but it is encoded anyway,
     * because "the token format will never change" is not a property this method
     * can enforce.
     *
     * <p><b>{@code UriUtils.encodePathSegment}, not {@code URLEncoder}.</b> The
     * latter is {@code application/x-www-form-urlencoded}: it turns a space into
     * {@code +}, which inside a path segment is a literal plus and not a space.
     * Harmless for today's token, wrong the day the format changes — which is
     * the only reason to encode here at all.
     */
    public String unsubscribeUrl(String token) {
        return apiPublicBaseUrl + "/api/v1/public/unsubscribe/"
                + org.springframework.web.util.UriUtils.encodePathSegment(
                        token, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** "Name <addr>" or just "addr" when name blank — mirrors EmailProperties.fromHeader(). */
    public String fromHeader() {
        return fromHeader(null);
    }

    /**
     * The display name inside {@link #fromHeader(String)}: {@code "<organizer> via IMIN"} when the organizer
     * name and the address are both set, else the configured name, else null. The hub shows exactly this.
     */
    public String displayName(String organizer) {
        String viaImin = viaImin(organizer);
        if (viaImin != null) return viaImin;
        return fromName == null || fromName.isBlank() ? null : fromName;
    }

    /**
     * Per-organizer From: {@code "<organizer> via IMIN" <fromAddress>} on the configured address.
     * Blank organizer or blank address falls back to {@code fromName <fromAddress>}, or the bare address.
     */
    public String fromHeader(String organizer) {
        String display = displayName(organizer);
        if (display == null) return fromAddress;
        // The organizer name is free text, so it goes out as an RFC 5322 quoted-string; the configured name as set.
        String phrase = viaImin(organizer) == null ? display
                : "\"" + display.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return phrase + " <" + fromAddress + ">";
    }

    private String viaImin(String organizer) {
        String clean = com.imin.iminapi.marketing.render.OrganizerIdentity.singleLine(organizer);
        if (clean.isEmpty() || fromAddress == null || fromAddress.isBlank()) return null;
        return clean + " via IMIN";
    }
}
