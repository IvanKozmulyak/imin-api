package com.imin.iminapi.stripe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe webhook receivers. No auth — verified by HMAC signature inside the service.
 *
 * <p><b>Two endpoints, one per Stripe payload format.</b> Stripe ships two distinct
 * webhook payload shapes:
 *
 * <ul>
 *   <li><b>V1 events</b> — the legacy {@code event.type = "payment_intent.succeeded"}
 *       envelope with the full object embedded in {@code data.object}. Routed to
 *       {@link #receiveV1(HttpEntity)} at {@code /api/v1/stripe/webhook/v1}.
 *       Subscribe this endpoint to: {@code payment_intent.succeeded},
 *       {@code payment_intent.payment_failed}, {@code checkout.session.expired}.</li>
 *   <li><b>V2 thin events</b> — the new {@code object = "v2.core.event"} envelope
 *       that carries only the event id; the handler fetches the full payload via
 *       the v2 API. Routed to {@link #receiveV2(HttpEntity)} at
 *       {@code /api/v1/stripe/webhook/v2}. Subscribe to:
 *       {@code v2.core.account.requirements.updated},
 *       {@code v2.core.account.recipient.capability_status_updated}.</li>
 * </ul>
 *
 * <p>Each endpoint has its own signing secret ({@code STRIPE_WEBHOOK_SECRET_V1} and
 * {@code STRIPE_WEBHOOK_SECRET_V2}). This was previously a single mixed endpoint
 * with JSON-peek routing — Stripe's own dashboard flags that pattern with "you've
 * selected events with two different payload styles" because the two formats
 * require structurally different integration code.
 *
 * <p>We read the body via {@link HttpEntity}{@code <String>} so Spring hands us the
 * EXACT bytes Stripe sent. Jackson would normalize whitespace and break the HMAC.
 */
@RestController
@RequestMapping("/api/v1/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeWebhookService webhook;

    public StripeWebhookController(StripeWebhookService webhook) {
        this.webhook = webhook;
    }

    /** V1 payments / checkout events. See class javadoc for the subscription list. */
    @PostMapping("/webhook/v1")
    public ResponseEntity<Void> receiveV1(HttpEntity<String> entity) {
        HttpHeaders headers = entity.getHeaders();
        String sig = headers.getFirst("Stripe-Signature");
        String body = entity.getBody() == null ? "" : entity.getBody();
        long started = System.nanoTime();
        log.info("[stripe-webhook] /v1 received bodyBytes={} sigPresent={}",
                body.length(), sig != null && !sig.isBlank());
        try {
            webhook.handleV1Endpoint(body, sig);
            log.info("[stripe-webhook] /v1 acked 200 in {}ms", elapsedMs(started));
        } catch (RuntimeException e) {
            // Let the global exception handler shape the response; just record the rejection
            // here so the controller-level log line is symmetric with the success path.
            log.warn("[stripe-webhook] /v1 rejected in {}ms — {}", elapsedMs(started), e.getMessage());
            throw e;
        }
        return ResponseEntity.ok().build();
    }

    /** V2 thin events for Stripe Connect account state. See class javadoc. */
    @PostMapping("/webhook/v2")
    public ResponseEntity<Void> receiveV2(HttpEntity<String> entity) {
        HttpHeaders headers = entity.getHeaders();
        String sig = headers.getFirst("Stripe-Signature");
        String body = entity.getBody() == null ? "" : entity.getBody();
        long started = System.nanoTime();
        log.info("[stripe-webhook] /v2 received bodyBytes={} sigPresent={}",
                body.length(), sig != null && !sig.isBlank());
        try {
            webhook.handleV2Endpoint(body, sig);
            log.info("[stripe-webhook] /v2 acked 200 in {}ms", elapsedMs(started));
        } catch (RuntimeException e) {
            log.warn("[stripe-webhook] /v2 rejected in {}ms — {}", elapsedMs(started), e.getMessage());
            throw e;
        }
        return ResponseEntity.ok().build();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
