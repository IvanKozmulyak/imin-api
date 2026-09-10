package com.imin.iminapi.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guest checkout recorded no terms acceptance at all, and the marketing consent
 * proof was a hardcoded English sentence describing a checkbox the buyer site
 * owns. Both facts are captured on the buy page, and the Order only exists at
 * webhook fulfilment, so they have to survive the Stripe round trip.
 */
class CheckoutConsentTest {

    @Test
    void the_round_trip_through_stripe_metadata_preserves_both_facts() {
        Map<String, String> meta = new HashMap<>();
        new CheckoutConsent(true, "I agree to receive emails from this organiser").putInto(meta);

        CheckoutConsent back = CheckoutConsent.fromMetadata(meta);

        assertThat(back.acceptedTerms()).isTrue();
        assertThat(back.marketingOptInProofText())
                .isEqualTo("I agree to receive emails from this organiser");
    }

    /** Absent keys mean "not recorded" — every order placed before this shipped. */
    @Test
    void absent_fields_are_omitted_rather_than_written_as_false_or_null() {
        Map<String, String> meta = new HashMap<>();
        CheckoutConsent.NONE.putInto(meta);

        assertThat(meta).isEmpty();
        assertThat(CheckoutConsent.fromMetadata(meta).acceptedTerms()).isFalse();
        assertThat(CheckoutConsent.fromMetadata(null).marketingOptInProofText()).isNull();
    }

    /** Stripe caps a metadata value at 500 chars, and so does the column. */
    @Test
    void an_overlong_proof_text_is_truncated_once_and_blank_becomes_null() {
        assertThat(new CheckoutConsent(false, "x".repeat(900)).marketingOptInProofText())
                .hasSize(500);
        assertThat(new CheckoutConsent(false, "   ").marketingOptInProofText()).isNull();
    }

    @Test
    void applyTo_only_ever_writes_and_never_clears_an_existing_record() {
        Order order = new Order();
        new CheckoutConsent(true, "read this").applyTo(order);
        var stamped = order.getTermsAcceptedAt();

        assertThat(stamped).isNotNull();
        assertThat(order.getMarketingOptInProof()).isEqualTo("read this");

        // The NONE value is what every legacy caller passes — it must not wipe evidence.
        CheckoutConsent.NONE.applyTo(order);
        assertThat(order.getTermsAcceptedAt()).isEqualTo(stamped);
        assertThat(order.getMarketingOptInProof()).isEqualTo("read this");
    }

    @Test
    void a_declined_or_absent_terms_box_leaves_the_timestamp_null() {
        Order order = new Order();
        new CheckoutConsent(false, null).applyTo(order);
        assertThat(order.getTermsAcceptedAt()).isNull();
    }
}
