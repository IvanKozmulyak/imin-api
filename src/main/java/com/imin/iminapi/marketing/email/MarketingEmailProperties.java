package com.imin.iminapi.marketing.email;

import com.imin.iminapi.audience.service.MailboxSyntax;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Organization;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Sending identity for bulk marketing email (spec §3). The From address, the Resend key and the Reply-To fallback are
 * configured, not assumed: a marketing subdomain with its own SPF/DKIM and key exists only once
 * IMIN_MARKETING_FROM_ADDRESS and IMIN_MARKETING_RESEND_API_KEY name it; blank keeps the transactional key.
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

    /** Resend key for campaign batches (IMIN_MARKETING_RESEND_API_KEY); blank falls back to imin.email.api-key. */
    private String resendApiKey = "";

    /** Reply-To when the organizer's legal contact is not one mailable address (IMIN_MARKETING_REPLY_TO); blank = none. */
    private String replyTo = "";

    /** Wait between two batches of one drive, under Resend's per-team request rate; zero sends back to back. */
    private Duration batchPause = Duration.ZERO;

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getBuyerSiteBaseUrl() { return buyerSiteBaseUrl; }
    public void setBuyerSiteBaseUrl(String v) { this.buyerSiteBaseUrl = v; }
    public String getApiPublicBaseUrl() { return apiPublicBaseUrl; }
    public void setApiPublicBaseUrl(String v) { this.apiPublicBaseUrl = v; }
    public String getResendApiKey() { return resendApiKey; }
    public void setResendApiKey(String v) { this.resendApiKey = v; }
    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String v) { this.replyTo = v; }
    public Duration getBatchPause() { return batchPause; }
    public void setBatchPause(Duration v) { this.batchPause = v; }

    /** The key campaign batches authenticate with: the marketing key when set, else the transactional one. */
    public String resendApiKey(EmailProperties email) {
        if (resendApiKey != null && !resendApiKey.isBlank()) return resendApiKey.trim();
        String fallback = email == null ? null : email.getApiKey();
        return fallback == null ? "" : fallback.trim();
    }

    /**
     * Where fan replies go: the organizer's legal contact when it is one mailable address (it is already printed in
     * every footer), else the configured IMIN inbox, else null (no Reply-To header).
     */
    public String replyTo(Organization org) {
        // Stripped at the ends only: a newline inside (a header injection attempt) still fails the syntax check.
        String legal = org == null || org.getLegalContact() == null ? "" : org.getLegalContact().strip();
        if (MailboxSyntax.isDeliverable(legal)) return legal;
        String fallback = replyTo == null ? "" : replyTo.strip();
        return MailboxSyntax.isDeliverable(fallback) ? fallback : null;
    }

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
