package com.imin.iminapi.stripe;

import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.service.event.InventoryService;
import com.stripe.StripeClient;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit-style tests for {@link StripeWebhookService}. Each test builds a real Stripe-shaped
 * V1 event JSON payload + HMAC signature using {@link Webhook.Util#computeHmacSha256}, so
 * the service's own {@code Webhook.constructEvent} path runs end-to-end against the same
 * secret without needing the Stripe SDK to be reachable on the network.
 */
class StripeWebhookServiceTest {

    private static final String SECRET = "whsec_test_secret";

    private StripeClient stripeClient;
    private StripeProperties props;
    private PromoCodeRepository promos;
    private InventoryService inventoryService;
    private StripeWebhookService svc;

    @BeforeEach
    void setUp() {
        stripeClient = mock(StripeClient.class);
        promos = mock(PromoCodeRepository.class);
        inventoryService = mock(InventoryService.class);
        props = new StripeProperties();
        props.setWebhookSecret(SECRET);
        svc = new StripeWebhookService(stripeClient, props, promos, inventoryService);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Sign a body with {@link #SECRET} the same way Stripe signs outbound webhooks.
     */
    private String sign(String body) throws Exception {
        long ts = Instant.now().getEpochSecond();
        String signedPayload = ts + "." + body;
        String sig = Webhook.Util.computeHmacSha256(SECRET, signedPayload);
        return "t=" + ts + ",v1=" + sig;
    }

    /**
     * Build a minimal {@code checkout.session.completed} (or .expired) JSON payload that
     * deserializes into a {@code Session} object with the given metadata.
     */
    private String sessionEvent(String type, String paymentStatus, String metadataJson) {
        // The webhook event envelope. data.object is the inlined Session.
        return """
            {
              "id": "evt_test_%s",
              "object": "event",
              "type": "%s",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "data": {
                "object": {
                  "id": "cs_test_%s",
                  "object": "checkout.session",
                  "payment_status": %s,
                  "metadata": %s
                }
              }
            }
            """.formatted(
                UUID.randomUUID().toString().substring(0, 8),
                type,
                Instant.now().getEpochSecond(),
                UUID.randomUUID().toString().substring(0, 8),
                paymentStatus == null ? "null" : "\"" + paymentStatus + "\"",
                metadataJson);
    }

    // ── checkout.session.completed → confirmSold ───────────────────────────────

    @Test
    void completedPaid_withInventoryMetadata_callsConfirmSold() throws Exception {
        UUID tierId = UUID.randomUUID();
        String metaJson = "{\"tier_id\":\"" + tierId + "\",\"qty\":\"2\"}";
        String body = sessionEvent("checkout.session.completed", "paid", metaJson);

        svc.handle(body, sign(body));

        verify(inventoryService).confirmSold(eq(tierId), eq(2));
    }

    @Test
    void completedPaid_withoutMetadata_skipsInventory_but_doesNotThrow() throws Exception {
        // Legacy session that pre-dates the inventory wiring — we still ack the webhook
        // and continue with promo redemption (which is also a no-op here, no promo_id).
        String body = sessionEvent("checkout.session.completed", "paid", "null");

        svc.handle(body, sign(body));

        verify(inventoryService, never()).confirmSold(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void completedPaid_withMalformedTierId_skipsInventory() throws Exception {
        String metaJson = "{\"tier_id\":\"not-a-uuid\",\"qty\":\"2\"}";
        String body = sessionEvent("checkout.session.completed", "paid", metaJson);

        svc.handle(body, sign(body));

        verify(inventoryService, never()).confirmSold(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void completedUnpaid_skipsInventory() throws Exception {
        UUID tierId = UUID.randomUUID();
        String metaJson = "{\"tier_id\":\"" + tierId + "\",\"qty\":\"2\"}";
        String body = sessionEvent("checkout.session.completed", "unpaid", metaJson);

        svc.handle(body, sign(body));

        // Only "paid" payment_status triggers confirmSold; async/unpaid sessions are
        // observed but skipped (would be picked up by async_payment_succeeded later).
        verify(inventoryService, never()).confirmSold(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    // ── checkout.session.expired → releaseReservation ──────────────────────────

    @Test
    void expired_withInventoryMetadata_callsReleaseReservation() throws Exception {
        UUID tierId = UUID.randomUUID();
        String metaJson = "{\"tier_id\":\"" + tierId + "\",\"qty\":\"3\"}";
        String body = sessionEvent("checkout.session.expired", null, metaJson);

        svc.handle(body, sign(body));

        verify(inventoryService).releaseReservation(eq(tierId), eq(3));
    }

    @Test
    void expired_withoutMetadata_skipsRelease() throws Exception {
        String body = sessionEvent("checkout.session.expired", null, "null");

        svc.handle(body, sign(body));

        verify(inventoryService, never()).releaseReservation(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
