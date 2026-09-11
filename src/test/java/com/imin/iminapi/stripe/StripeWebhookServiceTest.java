package com.imin.iminapi.stripe;

import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.event.InventoryService;
import com.imin.iminapi.service.ticket.PaidCheckoutService;
import com.stripe.StripeClient;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-style tests for the V1 endpoint of {@link StripeWebhookService}. Each test builds
 * a real Stripe-shaped V1 event JSON payload + HMAC signature using
 * {@link Webhook.Util#computeHmacSha256}, so the service's own {@code Webhook.constructEvent}
 * path runs end-to-end against the same secret without needing the Stripe SDK to be
 * reachable on the network.
 */
class StripeWebhookServiceTest {

    private static final String SECRET = "whsec_test_secret";
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    private StripeClient stripeClient;
    private StripeProperties props;
    private PromoCodeRepository promos;
    private InventoryService inventoryService;
    private WebhookEventDedupService dedup;
    private PaidCheckoutService paidCheckoutService;
    private com.imin.iminapi.refund.RefundService refundService;
    private SettlementIngestService settlementIngest;
    private com.imin.iminapi.dispute.DisputeIngestService disputeIngest;
    private CheckoutAmountVerifier amountVerifier;
    private StripeWebhookService svc;

    /**
     * Set of event ids that have already been "recorded" by tryRecord — the
     * mocked dedup mirrors the real semantics: first call returns true, any
     * later call with the same id returns false. This lets us test the
     * replay-once-only contract without spinning up a database.
     */
    private Set<String> recorded;

    @BeforeEach
    void setUp() {
        stripeClient = mock(StripeClient.class);
        promos = mock(PromoCodeRepository.class);
        inventoryService = mock(InventoryService.class);
        dedup = mock(WebhookEventDedupService.class);
        paidCheckoutService = mock(PaidCheckoutService.class);
        refundService = mock(com.imin.iminapi.refund.RefundService.class);
        settlementIngest = mock(SettlementIngestService.class);
        disputeIngest = mock(com.imin.iminapi.dispute.DisputeIngestService.class);
        amountVerifier = mock(CheckoutAmountVerifier.class);

        // Default: nothing to recompute, so the amount gate stands aside. The two mismatch
        // tests below stub a real verdict.
        when(amountVerifier.verify(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new CheckoutAmountVerifier.Result(false, true, null, null, "test default"));

        // Default: issuance succeeds (first-time). Promo increment is now gated on this returning
        // true (so a duplicate delivery can't double-count); the "never increments" cases below
        // are driven by missing promo metadata, not by issuance returning false.
        when(paidCheckoutService.issuePaidOrder(any(PaymentIntent.class), any())).thenReturn(true);

        recorded = new HashSet<>();
        when(dedup.tryRecord(anyString(), anyString())).thenAnswer(inv -> {
            String eventId = inv.getArgument(0);
            return recorded.add(eventId);
        });

        props = new StripeProperties();
        props.setWebhookSecretV1(SECRET);
        svc = new StripeWebhookService(stripeClient, props, promos, inventoryService, dedup,
                paidCheckoutService, refundService, settlementIngest, disputeIngest, amountVerifier,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Sign a body with {@link #SECRET} the same way Stripe signs outbound webhooks. */
    private String sign(String body) throws Exception {
        long ts = Instant.now().getEpochSecond();
        String signedPayload = ts + "." + body;
        String sig = Webhook.Util.computeHmacSha256(SECRET, signedPayload);
        return "t=" + ts + ",v1=" + sig;
    }

    /**
     * Build a minimal {@code checkout.session.*} JSON payload that deserializes
     * into a {@code Session} object with the given metadata.
     */
    private String sessionEvent(String eventId, String type, String paymentStatus, String metadataJson) {
        return """
            {
              "id": "%s",
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
                eventId,
                type,
                Instant.now().getEpochSecond(),
                UUID.randomUUID().toString().substring(0, 8),
                paymentStatus == null ? "null" : "\"" + paymentStatus + "\"",
                metadataJson);
    }

    /**
     * Build a minimal {@code payment_intent.*} JSON payload that deserializes into
     * a {@code PaymentIntent} object with the given metadata.
     */
    private String paymentIntentEvent(String eventId, String type, String metadataJson) {
        return paymentIntentEvent(eventId, type, metadataJson, null);
    }

    /** As above, with an optional top-level {@code account} — i.e. a Connect-scoped delivery. */
    private String paymentIntentEvent(String eventId, String type, String metadataJson, String account) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "%s",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              %s
              "data": {
                "object": {
                  "id": "pi_test_%s",
                  "object": "payment_intent",
                  "amount": 1000,
                  "currency": "eur",
                  "status": "succeeded",
                  "metadata": %s
                }
              }
            }
            """.formatted(
                eventId,
                type,
                Instant.now().getEpochSecond(),
                account == null ? "" : "\"account\": \"" + account + "\",",
                UUID.randomUUID().toString().substring(0, 8),
                metadataJson);
    }

    /** A {@code payout.*} envelope with NO connected account — the wrong scope for payouts. */
    private String platformScopedPayoutEvent(String eventId) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "payout.paid",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "data": {
                "object": {
                  "id": "po_test_1",
                  "object": "payout",
                  "amount": 4200,
                  "currency": "eur",
                  "status": "paid"
                }
              }
            }
            """.formatted(eventId, Instant.now().getEpochSecond());
    }

    private static String metaJson(UUID reservationId, UUID tierId, int qty) {
        return "{\"reservation_id\":\"" + reservationId + "\",\"tier_id\":\"" + tierId
                + "\",\"qty\":\"" + qty + "\"}";
    }

    /**
     * Build a minimal {@code charge.refund.updated} JSON payload. The data.object
     * for this event is a Refund directly.
     */
    private String refundUpdatedEvent(String eventId, String refundId, String status,
                                      String failureReason) {
        return refundEvent("charge.refund.updated", eventId, refundId, status, failureReason);
    }

    private String refundEvent(String type, String eventId, String refundId, String status,
                               String failureReason) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "%s",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "data": {
                "object": {
                  "id": "%s",
                  "object": "refund",
                  "amount": 5000,
                  "currency": "eur",
                  "charge": "ch_test_xyz",
                  "payment_intent": "pi_test_xyz",
                  "status": "%s",
                  "failure_reason": %s
                }
              }
            }
            """.formatted(
                eventId,
                type,
                Instant.now().getEpochSecond(),
                refundId,
                status,
                failureReason == null ? "null" : "\"" + failureReason + "\"");
    }

    // ── charge.refund.updated → handleWebhookStatusChange ─────────────────────

    @org.junit.jupiter.api.Test
    void chargeRefundUpdated_succeeded_callsHandleWebhookStatusChangeWithSUCCEEDED() throws Exception {
        String body = refundUpdatedEvent("evt_refund_1", "re_test_1", "succeeded", null);

        svc.handleV1Endpoint(body, sign(body));

        verify(refundService).handleWebhookStatusChange(
            eq("re_test_1"),
            eq(com.imin.iminapi.refund.RefundStatus.SUCCEEDED),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            eq("pi_test_xyz"),
            eq("ch_test_xyz"),
            eq(5000L));
    }

    @org.junit.jupiter.api.Test
    void chargeRefundUpdated_failed_passesFailureReason() throws Exception {
        String body = refundUpdatedEvent("evt_refund_2", "re_test_2", "failed", "expired_or_canceled_card");

        svc.handleV1Endpoint(body, sign(body));

        verify(refundService).handleWebhookStatusChange(
            eq("re_test_2"),
            eq(com.imin.iminapi.refund.RefundStatus.FAILED),
            eq("expired_or_canceled_card"),
            eq("expired_or_canceled_card"),
            eq("pi_test_xyz"),
            eq("ch_test_xyz"),
            eq(5000L));
    }

    @org.junit.jupiter.api.Test
    void chargeRefundUpdated_replayIsDedupSkipped() throws Exception {
        String body = refundUpdatedEvent("evt_refund_dedup", "re_test_3", "succeeded", null);

        svc.handleV1Endpoint(body, sign(body));
        svc.handleV1Endpoint(body, sign(body));   // replay

        // refundService called exactly once despite two webhook deliveries
        org.mockito.Mockito.verify(refundService, org.mockito.Mockito.times(1))
            .handleWebhookStatusChange(eq("re_test_3"), any(), any(), any(), any(), any(), any());
    }

    @org.junit.jupiter.api.Test
    void refundUpdated_unifiedEvent_succeeded_callsHandleWebhookStatusChange() throws Exception {
        // refund.updated is the unified event (all payment methods), not the legacy
        // charge.refund.updated alias — it must drive the same status transition.
        String body = refundEvent("refund.updated", "evt_refund_u1", "re_test_u1", "succeeded", null);

        svc.handleV1Endpoint(body, sign(body));

        verify(refundService).handleWebhookStatusChange(
            eq("re_test_u1"),
            eq(com.imin.iminapi.refund.RefundStatus.SUCCEEDED),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            eq("pi_test_xyz"),
            eq("ch_test_xyz"),
            eq(5000L));
    }

    @org.junit.jupiter.api.Test
    void refundFailed_unifiedEvent_passesFailureReason() throws Exception {
        String body = refundEvent("refund.failed", "evt_refund_f1", "re_test_f1", "failed", "lost_or_stolen_card");

        svc.handleV1Endpoint(body, sign(body));

        verify(refundService).handleWebhookStatusChange(
            eq("re_test_f1"),
            eq(com.imin.iminapi.refund.RefundStatus.FAILED),
            eq("lost_or_stolen_card"),
            eq("lost_or_stolen_card"),
            eq("pi_test_xyz"),
            eq("ch_test_xyz"),
            eq(5000L));
    }

    // ── endpoint must REJECT (non-2xx) so Stripe retries, never silently 200 ──────

    @Test
    void v1_invalidSignature_throwsSoStripeRetries() {
        // A bad signature must surface as an exception (controller → 400) so Stripe re-delivers,
        // and must NOT reach any handler — a forged event can't poison the dedup table either.
        String body = "{\"id\":\"evt_forged\",\"type\":\"payment_intent.succeeded\"}";

        assertThatThrownBy(() -> svc.handleV1Endpoint(body, "t=1,v1=deadbeef"))
                .isInstanceOf(ApiException.class);

        verify(dedup, never()).tryRecord(anyString(), anyString());
        verify(paidCheckoutService, never()).issuePaidOrder(any(PaymentIntent.class), any());
    }

    @Test
    void v1_missingSecret_throwsServiceUnavailable() {
        props.setWebhookSecretV1(null);

        assertThatThrownBy(() -> svc.handleV1Endpoint("{}", "t=1,v1=whatever"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void v1_missingSignatureHeader_throws() {
        assertThatThrownBy(() -> svc.handleV1Endpoint("{}", null))
                .isInstanceOf(ApiException.class);
    }

    // ── payment_intent.succeeded → confirmSold + promo increment ───────────────

    @Test
    void paymentIntentSucceeded_withReservationId_callsConfirmSold() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = paymentIntentEvent("evt_pi_success_1", "payment_intent.succeeded",
                metaJson(reservationId, tierId, 2));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService).confirmSold(eq(reservationId));
    }

    @Test
    void paymentIntentSucceeded_replayedTwice_runsExactlyOnce() throws Exception {
        // Same event id delivered twice — the second delivery must be a no-op.
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID promoId = UUID.randomUUID();
        String metaJson = "{\"reservation_id\":\"" + reservationId
                + "\",\"tier_id\":\"" + tierId
                + "\",\"qty\":\"2\",\"promo_id\":\"" + promoId + "\"}";
        String eventId = "evt_pi_dedupe_1";
        String body = paymentIntentEvent(eventId, "payment_intent.succeeded", metaJson);
        when(promos.incrementUsedCount(promoId)).thenReturn(1);

        svc.handleV1Endpoint(body, sign(body));
        // Replay the same event id (Stripe at-least-once delivery).
        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, times(1)).confirmSold(eq(reservationId));
        verify(promos, times(1)).incrementUsedCount(eq(promoId));
    }

    @Test
    void paymentIntentSucceeded_withoutReservationId_skipsInventory_butDoesNotThrow() throws Exception {
        // Legacy PI that pre-dates the reservation_id metadata — must not throw.
        String body = paymentIntentEvent("evt_pi_legacy_1", "payment_intent.succeeded", "{}");

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, never()).confirmSold(any(UUID.class));
    }

    @Test
    void paymentIntentSucceeded_withMalformedReservationId_skipsInventory() throws Exception {
        String metaJson = "{\"reservation_id\":\"not-a-uuid\"}";
        String body = paymentIntentEvent("evt_pi_malformed_1", "payment_intent.succeeded", metaJson);

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, never()).confirmSold(any(UUID.class));
    }

    @Test
    void paymentIntentSucceeded_invokesPaidCheckoutServiceWithPI() throws Exception {
        // Fulfilment: after inventory + (optional) promo, PaidCheckoutService.issuePaidOrder
        // is invoked with the deserialized PI so the Order + Ticket rows get persisted.
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = paymentIntentEvent("evt_pi_issuance_1", "payment_intent.succeeded",
                metaJson(reservationId, tierId, 1));

        svc.handleV1Endpoint(body, sign(body));

        verify(paidCheckoutService).issuePaidOrder(any(PaymentIntent.class), any());
    }

    // ── stripe-9 — Stripe reads must happen BEFORE the tier row lock ──────────────
    @Test
    void paymentIntentSucceeded_resolvesTheBuyerFromStripeBeforeLockingTheTier() throws Exception {
        // confirmSold runs SELECT … FOR UPDATE on the ticket tier and holds it until this
        // transaction commits. Resolving the buyer (charges().retrieve + sessions().list, both
        // blocking, 80s default read timeout) inside that window put every concurrent buyer of
        // the tier behind Stripe's latency, so the resolution has to come first.
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = paymentIntentEvent("evt_pi_order_of_ops", "payment_intent.succeeded",
                metaJson(reservationId, tierId, 1));

        svc.handleV1Endpoint(body, sign(body));

        var inOrder = org.mockito.Mockito.inOrder(paidCheckoutService, inventoryService);
        inOrder.verify(paidCheckoutService).prepareIssuance(any(PaymentIntent.class));
        inOrder.verify(inventoryService).confirmSold(reservationId);
        inOrder.verify(paidCheckoutService).issuePaidOrder(any(PaymentIntent.class), any());
    }

    @Test
    void paymentIntentSucceeded_withoutPromo_stillInvokesIssuance() throws Exception {
        // The old early-return on missing promo_id would have skipped issuance.
        // Lock the correct behavior: issuance fires regardless of promo.
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = paymentIntentEvent("evt_pi_no_promo_issuance", "payment_intent.succeeded",
                metaJson(reservationId, tierId, 1));

        svc.handleV1Endpoint(body, sign(body));

        verify(paidCheckoutService).issuePaidOrder(any(PaymentIntent.class), any());
    }

    @Test
    void paymentIntentFailed_doesNotInvokeIssuance() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = paymentIntentEvent("evt_pi_fail_no_issue", "payment_intent.payment_failed",
                metaJson(reservationId, tierId, 1));

        svc.handleV1Endpoint(body, sign(body));

        verify(paidCheckoutService, never()).issuePaidOrder(any(PaymentIntent.class), any());
    }

    // ── payment_intent.payment_failed → release ONLY the async-terminal case ──────

    @Test
    void paymentFailedDoesNotReleaseWhileRetryable() throws Exception {
        // A declined card leaves the PaymentIntent payable inside its Checkout Session, so the
        // seat must stay held; releasing it here is what produced [OVERSOLD] on the retry.
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        when(inventoryService.isAsyncProcessing(reservationId)).thenReturn(false);
        String body = paymentIntentEvent("evt_pi_fail_retryable", "payment_intent.payment_failed",
                metaJson(reservationId, tierId, 3));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, never()).releaseReservation(eq(reservationId), anyString());
    }

    @Test
    void paymentFailedReleasesWhenAsyncProcessing() throws Exception {
        // SEPA/iDEAL/Klarna: the intent reported `processing` days ago and has now definitively
        // failed. Nothing can retry it, so the hold is terminal.
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        when(inventoryService.isAsyncProcessing(reservationId)).thenReturn(true);
        String body = paymentIntentEvent("evt_pi_fail_async", "payment_intent.payment_failed",
                metaJson(reservationId, tierId, 3));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService).releaseReservation(eq(reservationId), eq("WEBHOOK_FAILED"));
    }

    @Test
    void paymentIntentProcessingExtendsTheHoldInsteadOfReleasing() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        props.setAsyncPaymentHoldDays(7);
        String body = paymentIntentEvent("evt_pi_processing_1", "payment_intent.processing",
                metaJson(reservationId, tierId, 2));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService).markAsyncProcessing(eq(reservationId),
                eq(NOW.plus(Duration.ofDays(7))));
        verify(inventoryService, never()).releaseReservation(any(UUID.class), anyString());
        verify(paidCheckoutService, never()).issuePaidOrder(any(PaymentIntent.class), any());
    }

    // ── payment_intent.canceled → the deterministic terminal release ───────────

    @Test
    void paymentIntentCanceledReleasesTheHold() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = paymentIntentEvent("evt_pi_canceled_1", "payment_intent.canceled",
                metaJson(reservationId, tierId, 3));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService).releaseReservation(eq(reservationId), eq("WEBHOOK_CANCELED"));
    }

    @Test
    void paymentIntentCanceled_replayedTwice_releasesOnce() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = paymentIntentEvent("evt_pi_cancel_dedupe", "payment_intent.canceled",
                metaJson(reservationId, tierId, 3));

        svc.handleV1Endpoint(body, sign(body));
        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, times(1)).releaseReservation(eq(reservationId), eq("WEBHOOK_CANCELED"));
    }

    // ── checkout.session.async_payment_* ──────────────────────────────────────

    @Test
    void asyncPaymentFailedReleasesTheHold() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = sessionEvent("evt_async_failed_1", "checkout.session.async_payment_failed",
                "unpaid", metaJson(reservationId, tierId, 2));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService).releaseReservation(eq(reservationId), eq("WEBHOOK_ASYNC_FAILED"));
    }

    @Test
    void asyncPaymentSucceededIsANoOp() throws Exception {
        // Fulfilment stays on payment_intent.succeeded, exactly as for checkout.session.completed.
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = sessionEvent("evt_async_ok_1", "checkout.session.async_payment_succeeded",
                "paid", metaJson(reservationId, tierId, 2));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, never()).confirmSold(any(UUID.class));
        verify(inventoryService, never()).releaseReservation(any(UUID.class), anyString());
        verify(paidCheckoutService, never()).issuePaidOrder(any(PaymentIntent.class), any());
    }

    // ── amount / currency verification before fulfilment ──────────────────────

    @Test
    void amountMismatchIssuesNothingAndKeepsTheReservation() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID promoId = UUID.randomUUID();
        String metaJson = "{\"reservation_id\":\"" + reservationId
                + "\",\"tier_id\":\"" + tierId
                + "\",\"qty\":\"2\",\"promo_id\":\"" + promoId + "\"}";
        // Priced at 2099; Stripe charged the payload's 1000.
        when(amountVerifier.verify(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new CheckoutAmountVerifier.Result(true, false, 2099L, "eur", null));
        String body = paymentIntentEvent("evt_pi_amount_mismatch", "payment_intent.succeeded", metaJson);

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, never()).confirmSold(any(UUID.class));
        verify(inventoryService, never()).releaseReservation(any(UUID.class), anyString());
        verify(paidCheckoutService, never()).issuePaidOrder(any(PaymentIntent.class), any());
        verify(promos, never()).incrementUsedCount(any());
    }

    @Test
    void currencyMismatchIssuesNothing() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        // Same total, wrong currency — the verifier reports it as the same mismatch verdict.
        when(amountVerifier.verify(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new CheckoutAmountVerifier.Result(true, false, 1000L, "gbp", null));
        String body = paymentIntentEvent("evt_pi_currency_mismatch", "payment_intent.succeeded",
                metaJson(reservationId, tierId, 1));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, never()).confirmSold(any(UUID.class));
        verify(paidCheckoutService, never()).issuePaidOrder(any(PaymentIntent.class), any());
    }

    @Test
    void matchingAmountStillFulfils() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        when(amountVerifier.verify(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new CheckoutAmountVerifier.Result(true, true, 1000L, "eur", null));
        String body = paymentIntentEvent("evt_pi_amount_match", "payment_intent.succeeded",
                metaJson(reservationId, tierId, 1));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService).confirmSold(eq(reservationId));
        verify(paidCheckoutService).issuePaidOrder(any(PaymentIntent.class), any());
    }

    // ── scope gate: both Dashboard endpoints post to this same URL ─────────────

    @Test
    void connectScopedPaymentIntentSucceededIsIgnored() throws Exception {
        // A connected-account copy of a fulfilment event is a duplicate of the platform one,
        // with its own event id — the dedup table cannot catch it.
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = paymentIntentEvent("evt_pi_connect_scope", "payment_intent.succeeded",
                metaJson(reservationId, tierId, 1), "acct_x");

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, never()).confirmSold(any(UUID.class));
        verify(paidCheckoutService, never()).issuePaidOrder(any(PaymentIntent.class), any());
        verify(paidCheckoutService, never()).prepareIssuance(any(PaymentIntent.class));
    }

    @Test
    void platformScopedPayoutIsIgnored() throws Exception {
        // Payouts settle ON the connected account; without one there is no org to resolve.
        String body = platformScopedPayoutEvent("evt_payout_wrong_scope");

        svc.handleV1Endpoint(body, sign(body));

        verify(settlementIngest, never()).ingestPayout(any(), any(), any());
    }

    // ── checkout.session.expired → releaseReservation ─────────────────────────

    @Test
    void expired_withReservationId_callsReleaseReservation() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = sessionEvent("evt_expired_1", "checkout.session.expired", null,
                metaJson(reservationId, tierId, 3));

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService).releaseReservation(eq(reservationId), eq("WEBHOOK_EXPIRED"));
    }

    @Test
    void expired_replayedTwice_releasesOnce() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String body = sessionEvent("evt_expired_dedupe", "checkout.session.expired", null,
                metaJson(reservationId, tierId, 3));

        svc.handleV1Endpoint(body, sign(body));
        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, times(1)).releaseReservation(eq(reservationId), eq("WEBHOOK_EXPIRED"));
    }

    @Test
    void expired_withoutReservationId_fallsBackToSessionIdLookup() throws Exception {
        // Legacy session created before the reservation_id metadata existed but
        // still in Stripe's retention window. The handler falls back to looking
        // up the reservation by session id.
        String body = sessionEvent("evt_expired_legacy", "checkout.session.expired", null, "null");

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, never()).releaseReservation(any(UUID.class), anyString());
        verify(inventoryService).releaseReservationBySessionId(
                ArgumentMatchers.startsWith("cs_test_"), eq("WEBHOOK_EXPIRED"));
    }

    // ── checkout.session.completed is a no-op ──────────────────────────────────

    @Test
    void completedPaid_isNoOp_noConfirmSoldOrPromoIncrement() throws Exception {
        // Fulfilment moved to payment_intent.succeeded; the completed event is
        // received but no longer drives inventory or promo accounting.
        UUID reservationId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID promoId = UUID.randomUUID();
        String metaJson = "{\"reservation_id\":\"" + reservationId
                + "\",\"tier_id\":\"" + tierId
                + "\",\"qty\":\"2\",\"promo_id\":\"" + promoId + "\"}";
        String body = sessionEvent("evt_completed_1", "checkout.session.completed", "paid", metaJson);

        svc.handleV1Endpoint(body, sign(body));

        verify(inventoryService, never()).confirmSold(any(UUID.class));
        verify(promos, never()).incrementUsedCount(any());
    }
}
