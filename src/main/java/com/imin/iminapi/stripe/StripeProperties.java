package com.imin.iminapi.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * `imin.stripe.*` configuration bound from {@code application.yaml} (which sources env vars).
 *
 * <ul>
 *   <li>{@code secret-key} — STRIPE_SECRET_KEY (sk_test_... locally, sk_live_... in prod).
 *       Required at startup — {@link StripeConfig} throws if it's blank.</li>
 *   <li>{@code webhook-secret} — STRIPE_WEBHOOK_SECRET (whsec_...). Required when the
 *       webhook endpoint receives traffic; warned but not fatal at startup.</li>
 *   <li>{@code application-fee-bps} — platform's cut of every Destination Charge in
 *       basis points. Default 500 = 5%.</li>
 *   <li>{@code public-return-url-base} — base URL for the buyer's success/cancel pages
 *       (the imin-public site). Default {@code http://localhost:3000}.</li>
 *   <li>{@code return-url-base} — fallback for the organizer's Stripe Connect
 *       onboarding return/refresh URL (the imin-webapp). Default
 *       {@code http://localhost:5173}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "imin.stripe")
public class StripeProperties {

    private String secretKey;
    private String webhookSecret;
    private int applicationFeeBps = 500;
    private String publicReturnUrlBase = "http://localhost:3000";
    private String returnUrlBase = "http://localhost:5173";

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public int getApplicationFeeBps() { return applicationFeeBps; }
    public void setApplicationFeeBps(int applicationFeeBps) { this.applicationFeeBps = applicationFeeBps; }

    public String getPublicReturnUrlBase() { return publicReturnUrlBase; }
    public void setPublicReturnUrlBase(String publicReturnUrlBase) { this.publicReturnUrlBase = publicReturnUrlBase; }

    public String getReturnUrlBase() { return returnUrlBase; }
    public void setReturnUrlBase(String returnUrlBase) { this.returnUrlBase = returnUrlBase; }
}
