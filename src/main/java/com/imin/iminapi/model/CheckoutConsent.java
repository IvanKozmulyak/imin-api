package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;

import java.util.Map;

/**
 * The two consent facts the buyer's browser carries into checkout (V97): whether
 * they accepted the terms of sale, and the verbatim sentence they read next to
 * the marketing checkbox.
 *
 * <p>Grouped into one value object for the same reason {@link CheckoutAttribution}
 * is, and it rides the identical proven path: public checkout request → Stripe
 * Session + PaymentIntent metadata → {@code Order} at fulfilment. Adding two more
 * positional parameters to five checkout overloads would have been the other
 * option; this one keeps the free and paid flows unable to drift, because both
 * end at {@link #applyTo(Order)}.
 *
 * <p>Both fields are optional. The buyer site has not shipped its half yet, and
 * an order that predates it must keep working exactly as before — so
 * {@link #NONE} writes nothing and {@code AudienceOrderProjector} falls back to
 * its own sentence when the proof text is absent.
 */
public record CheckoutConsent(boolean acceptedTerms, String marketingOptInProofText) {

    /** Metadata keys used on the Stripe Session/PaymentIntent. */
    public static final String META_ACCEPTED_TERMS = "terms_accepted";
    public static final String META_MARKETING_PROOF = "marketing_opt_in_proof";

    /**
     * {@code orders.marketing_opt_in_proof} is VARCHAR(500) and a Stripe metadata
     * value tops out at 500 characters, so the cap is the same on both sides and
     * an over-long checkbox label is truncated once, here, rather than blowing up
     * the Stripe call or the INSERT.
     */
    private static final int PROOF_MAX = 500;

    /** Nothing captured — internal callers and every flow that predates V97. */
    public static final CheckoutConsent NONE = new CheckoutConsent(false, null);

    public CheckoutConsent {
        marketingOptInProofText = clean(marketingOptInProofText);
    }

    /** Add the captured fields to a Stripe metadata map. Absent fields are omitted, not sent as "null". */
    public void putInto(Map<String, String> metadata) {
        if (acceptedTerms) metadata.put(META_ACCEPTED_TERMS, "true");
        if (marketingOptInProofText != null) metadata.put(META_MARKETING_PROOF, marketingOptInProofText);
    }

    /** Rebuild from Stripe metadata at fulfilment. Missing keys → not captured. */
    public static CheckoutConsent fromMetadata(Map<String, String> metadata) {
        if (metadata == null) return NONE;
        return new CheckoutConsent(
                "true".equals(metadata.get(META_ACCEPTED_TERMS)),
                metadata.get(META_MARKETING_PROOF));
    }

    /**
     * Stamp onto an order. Central so the free and paid paths cannot drift.
     *
     * <p>Only ever writes: a false {@code acceptedTerms} leaves the timestamp
     * null rather than clearing one, because absence means "not recorded" and
     * this object is also the NONE value every legacy caller passes.
     */
    public void applyTo(Order order) {
        if (acceptedTerms && order.getTermsAcceptedAt() == null) {
            order.setTermsAcceptedAt(Times.nowMicros());
        }
        if (marketingOptInProofText != null) {
            order.setMarketingOptInProof(marketingOptInProofText);
        }
    }

    private static String clean(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() > PROOF_MAX ? t.substring(0, PROOF_MAX) : t;
    }
}
