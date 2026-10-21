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
     * The buyer site root. Builds the footer's unsubscribe page link ({@link #unsubscribePageUrl})
     * and is the {@code {{eventUrl}}} fallback when a campaign has no linked event.
     */
    private String buyerSiteBaseUrl = "https://app.imin.wtf";

    /**
     * The API's own public base, no trailing slash. The {@code List-Unsubscribe} header
     * (RFC 8058 one-tap) posts here, because {@code PublicUnsubscribeController} owns the consent
     * write and is the sole suppression authority.
     *
     * <p>Defaults to production, not localhost: an unset env var must never put a
     * {@code http://localhost:8080} link in a buyer's inbox.
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
     * The API unsubscribe URL, used for the {@code List-Unsubscribe} header: {@code POST} is RFC 8058
     * one-click (the button Gmail and Apple Mail render themselves); {@code GET} redirects to
     * {@link #unsubscribePageUrl}. The footer link uses the page URL, not this one.
     *
     * <p>The token is encoded with {@code UriUtils.encodePathSegment}, not {@code URLEncoder}
     * (which turns a space into {@code +}, a literal plus in a path).
     */
    public String unsubscribeUrl(String token) {
        return apiPublicBaseUrl + "/api/v1/public/unsubscribe/"
                + org.springframework.web.util.UriUtils.encodePathSegment(
                        token, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The human unsubscribe page on the buyer site, linked from the email footer. */
    public String unsubscribePageUrl(String token) {
        String base = buyerSiteBaseUrl == null ? "" : buyerSiteBaseUrl.replaceAll("/+$", "");
        return base + "/unsubscribe/" + org.springframework.web.util.UriUtils.encodePathSegment(
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
