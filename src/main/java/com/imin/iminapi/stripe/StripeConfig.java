package com.imin.iminapi.stripe;

import com.stripe.StripeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the singleton {@link StripeClient} (the fluent SDK that exposes both the v1
 * and v2 surfaces — we use v2 for Connect account flows per the platform spec).
 *
 * STRIPE_SECRET_KEY is REQUIRED. If absent we fail fast at startup so the operator
 * can't accidentally deploy a build that would 500 the first time a buyer hits
 * checkout. STRIPE_WEBHOOK_SECRET is only required for the webhook endpoint;
 * we log a warning instead of failing so local dev without `stripe listen`
 * still boots.
 */
@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class StripeConfig {

    private static final Logger log = LoggerFactory.getLogger(StripeConfig.class);

    @Bean
    public StripeClient stripeClient(StripeProperties props) {
        String key = props.getSecretKey();
        if (key == null || key.isBlank()) {
            // Fail fast — see README for setup. The user must set STRIPE_SECRET_KEY before booting.
            throw new IllegalStateException("STRIPE_SECRET_KEY is not set — see README for setup");
        }
        if (props.getWebhookSecret() == null || props.getWebhookSecret().isBlank()) {
            log.warn("STRIPE_WEBHOOK_SECRET is not set — POST /api/v1/stripe/webhook will reject all requests. "
                    + "Run `stripe listen --thin-events ...` and export the printed whsec_... value.");
        }
        // We construct StripeClient with the API key only. Stripe-Java pins the API version
        // it was built against; we deliberately do NOT pin a custom version because the v2
        // endpoints we call (accounts, account_links, events) require the SDK's default.
        return new StripeClient(key);
    }
}
