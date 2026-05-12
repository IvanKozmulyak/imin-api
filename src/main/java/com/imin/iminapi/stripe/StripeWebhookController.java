package com.imin.iminapi.stripe;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe webhook receiver. No auth — verified by HMAC signature inside the service.
 *
 * <p>We read the body via {@link HttpEntity}{@code <String>} so Spring hands us the EXACT
 * bytes Stripe sent. Jackson would normalize whitespace and break the signature.
 */
@RestController
@RequestMapping("/api/v1/stripe")
public class StripeWebhookController {

    private final StripeWebhookService webhook;

    public StripeWebhookController(StripeWebhookService webhook) {
        this.webhook = webhook;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(HttpEntity<String> entity) {
        HttpHeaders headers = entity.getHeaders();
        String sig = headers.getFirst("Stripe-Signature");
        String body = entity.getBody() == null ? "" : entity.getBody();
        webhook.handle(body, sig);
        // Stripe only cares that we 2xx — anything else triggers retries.
        return ResponseEntity.ok().build();
    }
}
