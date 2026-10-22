package com.imin.iminapi.marketing.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Resend webhook config. {@code secret} is the Svix signing secret shown in the Resend dashboard ("whsec_...");
 * {@code marketingSecret} is the second team's, when marketing mail runs on its own Resend team, and a body signed
 * with either is accepted. {@code toleranceSeconds} bounds clock skew on the svix-timestamp (Standard-Webhooks default
 * 300s). {@code recipientGrace} is how long after an event was created a tagged campaign event whose recipient row
 * is still queued is answered 503 so Svix retries it after the batch commits.
 */
@ConfigurationProperties(prefix = "imin.marketing.resend-webhook")
public class ResendWebhookProperties {
    private String secret = "";
    private String marketingSecret = "";
    private long toleranceSeconds = 300;
    private Duration recipientGrace = Duration.ofMinutes(30);

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getMarketingSecret() { return marketingSecret; }
    public void setMarketingSecret(String marketingSecret) { this.marketingSecret = marketingSecret; }
    public long getToleranceSeconds() { return toleranceSeconds; }
    public void setToleranceSeconds(long toleranceSeconds) { this.toleranceSeconds = toleranceSeconds; }
    public Duration getRecipientGrace() { return recipientGrace; }
    public void setRecipientGrace(Duration recipientGrace) { this.recipientGrace = recipientGrace; }
}
