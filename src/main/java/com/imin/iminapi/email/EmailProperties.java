package com.imin.iminapi.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imin.email")
public class EmailProperties {
    private String apiKey = "";
    private String fromAddress = "";
    private String fromName = "";
    private String replyTo = "";
    // Organizer dashboard host (imin-webapp). Used to build password-reset and
    // account-notification links. In prod: https://dashboard.imin.wtf.
    private String appBaseUrl = "https://dashboard.imin.wtf";
    // Public buyer site host (imin-public). Used to build ticket / order /
    // recovery links in buyer-facing emails. In prod: https://app.imin.wtf.
    // Kept separate from appBaseUrl because the two SPAs live on different
    // subdomains and have disjoint routes — pointing a ticket link at the
    // dashboard 404s.
    private String buyerSiteBaseUrl = "https://app.imin.wtf";
    // Imin's internal notification inbox for new refund requests. Falls back to
    // replyTo, then fromAddress, when blank — populated at read time, not on set.
    private String refundRequestInbox = "";
    // Magic-link TTL in minutes. Knob for incident response without redeploying.
    private int refundRequestTokenTtlMinutes = 60;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }
    public String getAppBaseUrl() { return appBaseUrl; }
    public void setAppBaseUrl(String appBaseUrl) { this.appBaseUrl = appBaseUrl; }
    public String getBuyerSiteBaseUrl() { return buyerSiteBaseUrl; }
    public void setBuyerSiteBaseUrl(String buyerSiteBaseUrl) { this.buyerSiteBaseUrl = buyerSiteBaseUrl; }
    public String getRefundRequestInbox() { return refundRequestInbox; }
    public void setRefundRequestInbox(String refundRequestInbox) { this.refundRequestInbox = refundRequestInbox; }
    public int getRefundRequestTokenTtlMinutes() { return refundRequestTokenTtlMinutes; }
    public void setRefundRequestTokenTtlMinutes(int v) { this.refundRequestTokenTtlMinutes = v; }

    /** Convenience: "Name <addr@example.com>" or just "addr@example.com" if name blank. */
    public String fromHeader() {
        if (fromName == null || fromName.isBlank()) return fromAddress;
        return fromName + " <" + fromAddress + ">";
    }

    /** Pick refund-request inbox with fallback chain: explicit -> reply-to -> from. */
    public String resolveRefundRequestInbox() {
        if (refundRequestInbox != null && !refundRequestInbox.isBlank()) return refundRequestInbox;
        if (replyTo != null && !replyTo.isBlank()) return replyTo;
        return fromAddress;
    }
}
