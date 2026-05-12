package com.imin.iminapi.stripe;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.v2.core.Event;
import com.stripe.model.v2.core.EventNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Handles Stripe webhook deliveries on {@code POST /api/v1/stripe/webhook}.
 *
 * <p>We use the v2 "thin event" / EventNotification flow (the {@code stripe listen --thin-events}
 * variety described in the spec). In stripe-java the relevant entry point is
 * {@link StripeClient#parseEventNotification(String, String, String)}, which signature-verifies
 * the payload and returns a lightweight notification. We then fetch the full event via the v2
 * Events service so we get the typed payload.
 *
 * <p>No DB state is updated — the org's connect status is fetched live on every read by the
 * organizer dashboard, so the only thing we need to do here is log the new state.
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final StripeClient stripeClient;
    private final StripeProperties props;

    public StripeWebhookService(StripeClient stripeClient, StripeProperties props) {
        this.stripeClient = stripeClient;
        this.props = props;
    }

    /**
     * @param rawBody  the unmodified request body (signature is computed over the exact bytes).
     * @param sigHeader the value of the {@code Stripe-Signature} header.
     */
    public void handle(String rawBody, String sigHeader) {
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            // 503 — we can't verify, so reject. The operator should set STRIPE_WEBHOOK_SECRET.
            log.warn("Refusing Stripe webhook: STRIPE_WEBHOOK_SECRET not configured");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Stripe webhook handler not configured");
        }
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Missing Stripe-Signature header");
        }

        EventNotification notification;
        try {
            // Verifies HMAC signature against raw body + secret, then deserializes to a typed
            // EventNotification subclass. This is the "thin event" entry — payload contains only
            // id + type + metadata, not the full resource — so we fetch the full Event below.
            notification = stripeClient.parseEventNotification(rawBody, sigHeader, secret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid Stripe signature");
        }

        String type = notification.getType();
        String id = notification.getId();
        log.info("Stripe webhook received: type={} id={}", type, id);

        // Only the two account-state types we care about get the full-event fetch. Other types
        // are logged and ack'd silently so Stripe stops retrying.
        if ("v2.core.account.requirements.updated".equals(type)
                || "v2.core.account.recipient.capability_status_updated".equals(type)) {
            try {
                // Fetch the full Event from v2 — gives us typed access to event.data without
                // re-parsing the thin notification's metadata.
                Event full = stripeClient.v2().core().events().retrieve(id);
                log.info("Stripe account state event: type={} eventId={} created={}",
                        full.getType(), full.getId(), full.getCreated());
                // No DB state to update — status is fetched live per the user's instruction.
            } catch (StripeException e) {
                // Don't 500 — Stripe will retry. Log and ack.
                log.warn("Failed to fetch full Stripe event {} (type {}): {}", id, type, e.getMessage());
            }
        }
    }
}
