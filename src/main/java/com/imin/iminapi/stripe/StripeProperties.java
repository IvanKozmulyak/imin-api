package com.imin.iminapi.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * `imin.stripe.*` configuration bound from {@code application.yaml} (which sources env vars).
 *
 * <ul>
 *   <li>{@code secret-key} — STRIPE_SECRET_KEY (sk_test_... locally, sk_live_... in prod).
 *       Required at startup — {@link StripeConfig} throws if it's blank.</li>
 *   <li>{@code webhook-secret-v1} — STRIPE_WEBHOOK_SECRET_V1 (whsec_...). The signing
 *       secret for the {@code /api/v1/stripe/webhook/v1} endpoint that receives
 *       legacy-format ({@code event.type = "payment_intent.succeeded"} etc.) payments
 *       webhooks. Stripe's dashboard exposes these as "V1 events".</li>
 *   <li>{@code webhook-secret-v2} — STRIPE_WEBHOOK_SECRET_V2 (whsec_...). The signing
 *       secret for the {@code /api/v1/stripe/webhook/v2} endpoint that receives
 *       Stripe's V2 thin-event payloads ({@code object = "v2.core.event"}). The
 *       split exists because V1 and V2 deliver different JSON shapes; mixing them
 *       on one endpoint is a documentation hazard.</li>
 *   <li>{@code application-fee-bps} — platform's percentage cut of every Destination
 *       Charge in basis points. Default 500 = 5%.</li>
 *   <li>{@code application-fee-fixed-minor} — flat per-ticket platform fee in minor
 *       units (cents). Default 99 = €0.99. Total platform fee per checkout is
 *       {@code fixed × qty + round(subtotal × bps / 10000)}.</li>
 *   <li>{@code public-return-url-base} — base URL for the buyer's success/cancel pages
 *       (the imin-public site). Default {@code http://localhost:3000}.</li>
 *   <li>{@code return-url-base} — fallback for the organizer's Stripe Connect
 *       onboarding return/refresh URL (the imin-webapp). Default
 *       {@code http://localhost:5173}.</li>
 *   <li>{@code checkout-session-ttl-minutes} — how long a Stripe Checkout Session
 *       stays valid; mirrored into the TicketReservation.expires_at so the
 *       sweeper releases stale holds even if the session.expired webhook misses.
 *       Default 30 (Stripe's hard minimum).</li>
 *   <li>{@code payout-schedule-manual} — STRIPE_PAYOUT_SCHEDULE_MANUAL. Master
 *       kill-switch for Track B manual payouts (Phase 1/2). Default {@code false}:
 *       when false NOTHING flips an account schedule and no payout is ever created
 *       (the code is inert on deploy). Only enabled in prod AFTER the test-mode
 *       spike passes.</li>
 *   <li>{@code payout-buffer-days} — STRIPE_PAYOUT_BUFFER_DAYS. Days after
 *       {@code event.endsAt} before the post-event payout job fires. Default 3
 *       (covers most EU card availability lag).</li>
 *   <li>{@code payout-zone} — STRIPE_PAYOUT_ZONE. Timezone for resolving the
 *       payout buffer deadline (the business deadline, not the event's local
 *       zone). Default {@code Europe/Amsterdam}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "imin.stripe")
public class StripeProperties {

    private String secretKey;
    private String webhookSecretV1;
    private String webhookSecretV2;
    private int applicationFeeBps = 500;
    private int applicationFeeFixedMinor = 99;
    private String publicReturnUrlBase = "http://localhost:3000";
    private String returnUrlBase = "http://localhost:5173";
    private int checkoutSessionTtlMinutes = 30;

    // ----- Track B manual payouts (Phase 1/2). DEFAULT FALSE => inert on deploy. -----
    private boolean payoutScheduleManual = false;
    private int payoutBufferDays = 3;
    private String payoutZone = "Europe/Amsterdam";

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getWebhookSecretV1() { return webhookSecretV1; }
    public void setWebhookSecretV1(String webhookSecretV1) { this.webhookSecretV1 = webhookSecretV1; }

    public String getWebhookSecretV2() { return webhookSecretV2; }
    public void setWebhookSecretV2(String webhookSecretV2) { this.webhookSecretV2 = webhookSecretV2; }

    public int getApplicationFeeBps() { return applicationFeeBps; }
    public void setApplicationFeeBps(int applicationFeeBps) { this.applicationFeeBps = applicationFeeBps; }

    public int getApplicationFeeFixedMinor() { return applicationFeeFixedMinor; }
    public void setApplicationFeeFixedMinor(int applicationFeeFixedMinor) {
        this.applicationFeeFixedMinor = applicationFeeFixedMinor;
    }

    public String getPublicReturnUrlBase() { return publicReturnUrlBase; }
    public void setPublicReturnUrlBase(String publicReturnUrlBase) { this.publicReturnUrlBase = publicReturnUrlBase; }

    public String getReturnUrlBase() { return returnUrlBase; }
    public void setReturnUrlBase(String returnUrlBase) { this.returnUrlBase = returnUrlBase; }

    public int getCheckoutSessionTtlMinutes() { return checkoutSessionTtlMinutes; }
    public void setCheckoutSessionTtlMinutes(int checkoutSessionTtlMinutes) {
        this.checkoutSessionTtlMinutes = checkoutSessionTtlMinutes;
    }

    public boolean isPayoutScheduleManual() { return payoutScheduleManual; }
    public void setPayoutScheduleManual(boolean payoutScheduleManual) {
        this.payoutScheduleManual = payoutScheduleManual;
    }

    public int getPayoutBufferDays() { return payoutBufferDays; }
    public void setPayoutBufferDays(int payoutBufferDays) { this.payoutBufferDays = payoutBufferDays; }

    public String getPayoutZone() { return payoutZone; }
    public void setPayoutZone(String payoutZone) { this.payoutZone = payoutZone; }
}
