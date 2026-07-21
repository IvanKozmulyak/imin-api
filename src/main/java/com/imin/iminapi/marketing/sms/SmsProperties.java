package com.imin.iminapi.marketing.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMS provider CONNECTION config (creds + endpoint), distinct from
 * {@link MarketingSmsProperties} which is the DTO-surfaced <i>display policy</i>
 * (sender label, cap, window). Kept separate on purpose: secrets here must never
 * be serialized to the Channels tab.
 *
 * <p><b>enabled is DERIVED from the api key.</b> Blank key ⇒ {@link #isEnabled()}
 * false ⇒ {@link SmsSender} runs in <b>dry-run</b> (logs the would-be message,
 * never calls the provider). This is the safety default while SMS billing is
 * unresolved (see {@code docs/research/sms-provider-decision.md} §billing). SMS
 * is only ever sent for real when {@code IMIN_SMS_API_KEY} is set AND a live
 * campaign path is explicitly wired — neither is on by default.
 *
 * <p>Provider is <b>Bird (MessageBird)</b>; see the decision doc for the FR/EU
 * rationale. {@code senderId} duplicates the display value in
 * {@link MarketingSmsProperties#getSenderId()} — this is the one actually sent;
 * both default to {@code IMIN}. A future consolidation could unify them.
 */
@ConfigurationProperties(prefix = "imin.sms")
public class SmsProperties {

    /** Bird API access key. Blank ⇒ dry-run (the default). */
    private String apiKey = "";

    /** Bird REST base URL. Classic MessageBird REST; confirm against the live account at setup. */
    private String baseUrl = "https://rest.messagebird.com";

    /** Alphanumeric originator actually sent as the SMS "from" (FR: registered, ≤11 chars). */
    private String senderId = "IMIN";

    /** HMAC secret for the inbound STOP / delivery-receipt webhook. Blank ⇒ every webhook 401s (fail closed). */
    private String webhookSecret = "";

    /** Clock-skew tolerance (seconds) on the webhook timestamp header, if present. */
    private long webhookToleranceSeconds = 300;

    /** True only when a non-blank api key is configured. When false, {@link SmsSender} is in dry-run. */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public long getWebhookToleranceSeconds() { return webhookToleranceSeconds; }
    public void setWebhookToleranceSeconds(long webhookToleranceSeconds) {
        this.webhookToleranceSeconds = webhookToleranceSeconds;
    }
}
