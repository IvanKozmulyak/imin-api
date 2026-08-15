package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.security.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The native PaymentIntent surface, tested at the boundary that matters: what
 * this service hands to Stripe, and what it does to inventory when Stripe fails.
 *
 * <p>The response fields are the least interesting assertions here. The money
 * invariants — fee computed on the UNDISCOUNTED subtotal, the full fulfilment
 * metadata attached to the PaymentIntent, the destination and transfer group —
 * are only visible in the captured {@link PaymentIntentCreateParams}. Dropping
 * {@code putAllMetadata}, {@code setApplicationFeeAmount}, {@code setTransferData}
 * or {@code setTransferGroup} would leave a response-only suite entirely green
 * while every native buyer's payment goes unfulfilled or untransferred.
 *
 * <p>The prelude is stubbed here, so this class proves the native service passes
 * the shared numbers through untouched. That the shared numbers are themselves
 * right — in particular that a promo never shrinks the platform fee — is proved
 * against the REAL {@code reserveAndBuildMetadata} in
 * {@code StripeCheckoutServiceTest.reserveAndBuildMetadata_computesFeeOnUndiscountedSubtotal}.
 */
class NativePaymentIntentTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID TIER = UUID.randomUUID();
    private static final UUID RESERVATION = UUID.randomUUID();

    private com.stripe.StripeClient stripeClient;
    private com.stripe.service.PaymentIntentService paymentIntents;
    private StripeCheckoutService checkoutService;
    private com.imin.iminapi.service.event.InventoryService inventory;
    private StripePaymentIntentService service;

    @BeforeEach
    void setUp() throws Exception {
        stripeClient = mock(com.stripe.StripeClient.class);
        paymentIntents = mock(com.stripe.service.PaymentIntentService.class);
        when(stripeClient.paymentIntents()).thenReturn(paymentIntents);

        PaymentIntent created = new PaymentIntent();
        created.setId("pi_test_123");
        created.setClientSecret("pi_test_123_secret_abc");
        when(paymentIntents.create(any(PaymentIntentCreateParams.class))).thenReturn(created);

        checkoutService = mock(StripeCheckoutService.class);
        inventory = mock(com.imin.iminapi.service.event.InventoryService.class);
        service = new StripePaymentIntentService(stripeClient, checkoutService, inventory);
    }

    @Test
    void chargesSubtotalPlusFeeAndAttachesEverythingFulfilmentNeeds() throws Exception {
        // 2 x EUR 25.00, no promo. Fee = 5% of 5000 + 99 per ticket = 250 + 198 = 448.
        stubPrelude(5000L, 0L, 5000L, 448L, null);

        var intent = service.create(EVENT, TIER, 2, null, null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en");

        assertThat(intent.clientSecret()).isEqualTo("pi_test_123_secret_abc");
        assertThat(intent.paymentIntentId()).isEqualTo("pi_test_123");
        assertThat(intent.amountMinor()).isEqualTo(5448L);
        assertThat(intent.feeMinor()).isEqualTo(448L);
        assertThat(intent.currency()).isEqualTo("eur");

        ArgumentCaptor<PaymentIntentCreateParams> captor =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(paymentIntents).create(captor.capture());
        PaymentIntentCreateParams sent = captor.getValue();

        assertThat(sent.getAmount()).isEqualTo(5448L);
        assertThat(sent.getCurrency()).isEqualTo("eur");
        assertThat(sent.getApplicationFeeAmount()).isEqualTo(448L);
        assertThat(sent.getTransferData().getDestination()).isEqualTo("acct_test_org");
        assertThat(sent.getTransferGroup()).isEqualTo(EVENT.toString());

        // Everything PaidCheckoutService reads off the PI. A missing key here is
        // a charged buyer with no ticket, so assert each one by name.
        assertThat(sent.getMetadata())
                .containsEntry("reservation_id", RESERVATION.toString())
                .containsEntry("tier_id", TIER.toString())
                .containsEntry("qty", "2")
                .containsEntry("event_id", EVENT.toString())
                .containsEntry("ads_consent", "false")
                .containsEntry("marketing_opt_in", "false")
                .containsEntry("buyer_locale", "en")
                // The native-only additions. Without buyer_email the address is
                // unrecoverable at fulfilment: there is no Checkout Session to
                // list, and nothing reads receipt_email.
                .containsEntry("buyer_email", "buyer@example.test")
                .containsEntry("client", "native");
    }

    /**
     * The one place native and hosted money math genuinely diverge. The hosted
     * path expresses a discount as a Stripe Coupon scoped to the ticket product;
     * the native path subtracts it from the amount — and the fee must STILL be
     * computed on the undiscounted subtotal, or a promo silently shrinks the
     * platform's cut.
     */
    @Test
    void aPromoDiscountsTheTicketsAndNeverTheFee() throws Exception {
        // 2 x EUR 25.00 with 20% off. Discount 1000, net 4000. Fee still on 5000.
        stubPrelude(5000L, 1000L, 4000L, 448L, "promo-abc");

        var intent = service.create(EVENT, TIER, 2, "VECHIRKA20", null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en");

        assertThat(intent.amountMinor()).isEqualTo(4448L);
        // Identical to the no-promo case above. This is the assertion that fails
        // if someone writes computeFee(netTotal, ...).
        assertThat(intent.feeMinor()).isEqualTo(448L);

        ArgumentCaptor<PaymentIntentCreateParams> captor =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(paymentIntents).create(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualTo(4448L);
        assertThat(captor.getValue().getApplicationFeeAmount()).isEqualTo(448L);
        assertThat(captor.getValue().getMetadata()).containsEntry("promo_id", "promo-abc");
    }

    @Test
    void aFreeTotalIsRejectedBeforeAnythingIsReserved() {
        when(checkoutService.priceIt(EVENT, TIER, 1, null, null))
                .thenReturn(new StripeCheckoutService.Priced(null, null, null, 0L, 0L, 0L));

        assertThatThrownBy(() -> service.create(EVENT, TIER, 1, null, null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("/checkout");

        // The point of pricing before reserving: no hold was ever taken, so
        // there is nothing to release.
        verify(checkoutService, never()).reserveAndBuildMetadata(
                any(), any(), any(), anyInt(), any(), anyBoolean(), anyBoolean(),
                any(), any(), anyBoolean());
        verify(inventory, never()).releaseReservation(any(), any());
    }

    @Test
    void aStripeFailureReturnsTheSeatsToThePool() throws Exception {
        stubPrelude(5000L, 0L, 5000L, 448L, null);
        when(paymentIntents.create(any(PaymentIntentCreateParams.class)))
                .thenThrow(new com.stripe.exception.ApiException("boom", null, null, 500, null));

        assertThatThrownBy(() -> service.create(EVENT, TIER, 2, null, null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en"))
                .isInstanceOf(ApiException.class);

        verify(inventory).releaseReservation(RESERVATION, "STRIPE_PI_CREATE_FAILED");
    }

    /** Stamps the PI id on the hold so the sweeper can cancel it later (Step 8). */
    @Test
    void stampsThePaymentIntentIdOntoTheReservation() throws Exception {
        stubPrelude(5000L, 0L, 5000L, 448L, null);

        service.create(EVENT, TIER, 2, null, null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en");

        verify(inventory).attachSessionId(RESERVATION, "pi_test_123");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubPrelude(long subtotal, long discount, long net, long fee, String promoId) {
        var priced = new StripeCheckoutService.Priced(null, null, null, subtotal, discount, net);
        when(checkoutService.priceIt(any(), any(), anyInt(), any(), any()))
                .thenReturn(priced);

        var org = new com.imin.iminapi.model.Organization();
        org.setStripeAccountId("acct_test_org");

        var metadata = new java.util.HashMap<String, String>();
        metadata.put("reservation_id", RESERVATION.toString());
        metadata.put("tier_id", TIER.toString());
        metadata.put("qty", "2");
        metadata.put("event_id", EVENT.toString());
        metadata.put("ads_consent", "false");
        metadata.put("marketing_opt_in", "false");
        metadata.put("buyer_locale", "en");
        metadata.put("buyer_email", "buyer@example.test");
        metadata.put("client", "native");
        if (promoId != null) metadata.put("promo_id", promoId);

        when(checkoutService.reserveAndBuildMetadata(
                any(), any(), any(), anyInt(), any(), anyBoolean(), anyBoolean(),
                any(), any(), anyBoolean()))
                .thenReturn(new StripeCheckoutService.PaidPrelude(
                        null, null, org, null, RESERVATION, java.time.Instant.now(),
                        subtotal, discount, net, fee, "eur", metadata));
    }
}
