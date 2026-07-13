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
    private String fromName = "";
    // Public host used to build the RFC 8058 unsubscribe link in the footer +
    // List-Unsubscribe header. In prod: https://app.imin.wtf (imin-public).
    private String unsubscribeBaseUrl = "http://localhost:3000";

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getUnsubscribeBaseUrl() { return unsubscribeBaseUrl; }
    public void setUnsubscribeBaseUrl(String v) { this.unsubscribeBaseUrl = v; }

    /** "Name <addr>" or just "addr" when name blank — mirrors EmailProperties.fromHeader(). */
    public String fromHeader() {
        if (fromName == null || fromName.isBlank()) return fromAddress;
        return fromName + " <" + fromAddress + ">";
    }
}
