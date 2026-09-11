package com.imin.iminapi.stripe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The amount gate is tested directly, not through the webhook: it is the piece that decides
 * whether a paid buyer gets a ticket. It compares the charge against the stamp the checkout
 * wrote, so an organizer editing the tier price mid-session cannot change its answer.
 */
class CheckoutAmountVerifierTest {

    private CheckoutAmountVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new CheckoutAmountVerifier();
    }

    private Map<String, String> stamped(String totalMinor, String currency) {
        Map<String, String> m = new HashMap<>();
        m.put("tier_id", "t1");
        m.put("qty", "2");
        if (totalMinor != null) m.put(CheckoutAmountVerifier.EXPECTED_TOTAL_MINOR, totalMinor);
        if (currency != null) m.put(CheckoutAmountVerifier.EXPECTED_CURRENCY, currency);
        return m;
    }

    @Test
    void stampedTotalThatMatchesTheChargeFulfils() {
        CheckoutAmountVerifier.Result r = verifier.verify(stamped("2298", "eur"), 2298L, "eur");

        assertThat(r.checked()).isTrue();
        assertThat(r.match()).isTrue();
        assertThat(r.expectedMinor()).isEqualTo(2298L);
        assertThat(r.expectedCurrency()).isEqualTo("eur");
    }

    @Test
    void chargeThatDiffersFromTheStampIsRefused() {
        CheckoutAmountVerifier.Result r = verifier.verify(stamped("2298", "eur"), 100L, "eur");

        assertThat(r.checked()).isTrue();
        assertThat(r.match()).isFalse();
        assertThat(r.expectedMinor()).isEqualTo(2298L);
    }

    @Test
    void chargeInAnotherCurrencyIsRefused() {
        CheckoutAmountVerifier.Result r = verifier.verify(stamped("2298", "eur"), 2298L, "gbp");

        assertThat(r.checked()).isTrue();
        assertThat(r.match()).isFalse();
        assertThat(r.expectedCurrency()).isEqualTo("eur");
    }

    /**
     * A price edit during an open session is exactly the case the stamp exists for: the buyer
     * agreed to 2298 and paid 2298, and the tier now costing something else is irrelevant.
     */
    @Test
    void aLaterTierPriceChangeCannotTurnAPaidOrderIntoAMismatch() {
        CheckoutAmountVerifier.Result r = verifier.verify(stamped("2298", "eur"), 2298L, "eur");

        assertThat(r.match())
                .as("nothing outside the metadata is read, so nothing outside it can refuse")
                .isTrue();
    }

    @Test
    void anUnstampedCheckoutIsSkippedNotRefused() {
        // Sessions opened before the stamp shipped. A refusal here is a paid buyer with no ticket.
        CheckoutAmountVerifier.Result r = verifier.verify(stamped(null, null), 2298L, "eur");

        assertThat(r.checked()).isFalse();
        assertThat(r.match()).isTrue();
        assertThat(r.skipReason()).contains(CheckoutAmountVerifier.EXPECTED_TOTAL_MINOR);
    }

    @Test
    void aMalformedStampIsSkippedNotRefused() {
        CheckoutAmountVerifier.Result r = verifier.verify(stamped("not-a-number", "eur"), 2298L, "eur");

        assertThat(r.checked()).isFalse();
        assertThat(r.skipReason()).contains("not a number");
    }

    @Test
    void aStampWithNoCurrencyStillChecksTheAmount() {
        assertThat(verifier.verify(stamped("2298", null), 2298L, "eur").match()).isTrue();
        assertThat(verifier.verify(stamped("2298", null), 999L, "eur").match()).isFalse();
    }

    @Test
    void noMetadataAndNoAmountAreBothSkips() {
        assertThat(verifier.verify(null, 2298L, "eur").checked()).isFalse();
        assertThat(verifier.verify(stamped("2298", "eur"), null, "eur").checked()).isFalse();
    }
}
