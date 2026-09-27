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

    @Test
    void the_text_version_round_trips_through_stripe_metadata() {
        Map<String, String> meta = new HashMap<>();
        new CheckoutConsent(true, "Email me about Arty Farty's events.", "checkout-org-named-2026-09").putInto(meta);

        assertThat(meta).containsEntry(CheckoutConsent.META_MARKETING_TEXT_VERSION, "checkout-org-named-2026-09");
        assertThat(CheckoutConsent.fromMetadata(meta).marketingOptInTextVersion())
                .isEqualTo("checkout-org-named-2026-09");
    }

    /** A version names a sentence; with no sentence there is nothing for it to name. */
    @Test
    void a_text_version_without_proof_text_is_dropped() {
        CheckoutConsent consent = new CheckoutConsent(true, null, "checkout-org-named-2026-09");

        assertThat(consent.marketingOptInTextVersion()).isNull();
        Map<String, String> meta = new HashMap<>();
        consent.putInto(meta);
        assertThat(meta).doesNotContainKey(CheckoutConsent.META_MARKETING_TEXT_VERSION);
    }

    /** Truncating an id could turn it into another id, so an over-long one is dropped whole. */
    @Test
    void an_overlong_text_version_is_dropped_not_truncated() {
        assertThat(new CheckoutConsent(false, "read this", "v".repeat(33)).marketingOptInTextVersion()).isNull();
        assertThat(new CheckoutConsent(false, "read this", "v".repeat(32)).marketingOptInTextVersion())
                .hasSize(32);
    }

    @Test
    void a_blank_text_version_becomes_null_and_a_padded_one_is_trimmed() {
        assertThat(new CheckoutConsent(false, "read this", "   ").marketingOptInTextVersion()).isNull();
        assertThat(new CheckoutConsent(false, "read this", " v1 ").marketingOptInTextVersion()).isEqualTo("v1");
    }

    @Test
    void the_two_argument_form_carries_no_text_version() {
        assertThat(new CheckoutConsent(true, "read this").marketingOptInTextVersion()).isNull();
        assertThat(CheckoutConsent.NONE.marketingOptInTextVersion()).isNull();
    }

    @Test
    void applyTo_writes_the_text_version_and_NONE_never_clears_it() {
        Order order = new Order();
        new CheckoutConsent(true, "read this", "checkout-org-named-2026-09").applyTo(order);
        assertThat(order.getMarketingOptInTextVersion()).isEqualTo("checkout-org-named-2026-09");

        CheckoutConsent.NONE.applyTo(order);
        assertThat(order.getMarketingOptInTextVersion()).isEqualTo("checkout-org-named-2026-09");
    }
}
