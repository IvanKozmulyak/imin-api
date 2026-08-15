package com.imin.iminapi.stripe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checkout response discriminant. A client must never have to parse a URL
 * to learn whether it is holding a Stripe session or a finished free order.
 */
class CheckoutResponseShapeTest {

    @Test
    void stripeResultCarriesTheSessionId() {
        var r = StripeCheckoutService.CheckoutResult.stripe(
                "https://checkout.stripe.com/c/pay/cs_test_123", "cs_test_123");
        assertThat(r.kind()).isEqualTo("stripe");
        assertThat(r.sessionId()).isEqualTo("cs_test_123");
        assertThat(r.url()).startsWith("https://checkout.stripe.com/");
    }

    @Test
    void freeOrderResultCarriesTheOrderTokenAndNoSessionId() {
        var r = StripeCheckoutService.CheckoutResult.order(
                "https://app.imin.wtf/order/tok_abc", "tok_abc");
        assertThat(r.kind()).isEqualTo("order");
        assertThat(r.sessionId()).isNull();
        // The app must never have to slice this out of the URL.
        assertThat(r.orderToken()).isEqualTo("tok_abc");
    }

    @Test
    void stripeResultHasNoOrderToken() {
        var r = StripeCheckoutService.CheckoutResult.stripe(
                "https://checkout.stripe.com/c/pay/cs_test_123", "cs_test_123");
        assertThat(r.orderToken()).isNull();
    }
}
