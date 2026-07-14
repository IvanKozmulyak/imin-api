package com.imin.iminapi.marketing.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend webhook config. {@code secret} is the Svix signing secret shown in
 * the Resend dashboard ("whsec_..."). {@code toleranceSeconds} bounds clock
 * skew on the svix-timestamp (Standard-Webhooks default is 300s / 5 min).
 */
@ConfigurationProperties(prefix = "imin.marketing.resend-webhook")
public class ResendWebhookProperties {
    private String secret = "";
    private long toleranceSeconds = 300;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public long getToleranceSeconds() { return toleranceSeconds; }
    public void setToleranceSeconds(long toleranceSeconds) { this.toleranceSeconds = toleranceSeconds; }
}
