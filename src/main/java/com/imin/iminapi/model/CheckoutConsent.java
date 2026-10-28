package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;

import java.util.Map;

/**
 * The consent facts the buyer's browser carries into checkout (V97): whether
 * they accepted the terms of sale, the verbatim sentence they read next to
 * the marketing checkbox, and that sentence's version id (V151); on a paid web
 * checkout instead, the marketing notice they read with its version, and whether
 * they ticked the refuse box (V207).
 *
 * <p>Grouped into one value object for the same reason {@link CheckoutAttribution}
 * is, and it rides the identical proven path: public checkout request → Stripe
 * Session + PaymentIntent metadata → {@code Order} at fulfilment. Adding two more
 * positional parameters to five checkout overloads would have been the other
 * option; this one keeps the free and paid flows unable to drift, because both
 * end at {@link #applyTo(Order)}.
 *
 * <p>Every field is optional; {@link #NONE} writes nothing. Without proof text
 * {@code AudienceOrderProjector} records no explicit consent, and without notice
 * proof it records no soft opt-in: an absent notice means it was not shown.
 */
public record CheckoutConsent(boolean acceptedTerms, String marketingOptInProofText,
                              String marketingOptInTextVersion, String marketingNoticeProofText,
                              String marketingNoticeTextVersion, boolean marketingRefused) {

    /** Metadata keys used on the Stripe Session/PaymentIntent. */
    public static final String META_ACCEPTED_TERMS = "terms_accepted";
    public static final String META_MARKETING_PROOF = "marketing_opt_in_proof";
    public static final String META_MARKETING_TEXT_VERSION = "marketing_opt_in_text_version";
    public static final String META_NOTICE_PROOF = "marketing_notice_proof";
    public static final String META_NOTICE_TEXT_VERSION = "marketing_notice_text_version";
    public static final String META_MARKETING_REFUSED = "marketing_refused";

    /**
     * {@code orders.marketing_opt_in_proof} and {@code orders.marketing_notice_proof} are
     * VARCHAR(500) and a Stripe metadata value tops out at 500 characters, so the cap is the
     * same on both sides and an over-long sentence is truncated once, here, rather than
     * blowing up the Stripe call or the INSERT.
     */
    private static final int PROOF_MAX = 500;

    /** {@code orders.marketing_opt_in_text_version} and {@code marketing_notice_text_version} are VARCHAR(32). */
    static final int TEXT_VERSION_MAX = 32;

    /** Nothing captured — internal callers and every flow that predates V97. */
    public static final CheckoutConsent NONE = new CheckoutConsent(false, null, null);

    /**
     * A version names its sentence, so it is dropped without one. An over-long
     * version is dropped rather than truncated: a cut id could match a different version.
     */
    public CheckoutConsent {
        marketingOptInProofText = clean(marketingOptInProofText);
        marketingOptInTextVersion = marketingOptInProofText == null ? null : cleanVersion(marketingOptInTextVersion);
        marketingNoticeProofText = clean(marketingNoticeProofText);
        marketingNoticeTextVersion = marketingNoticeProofText == null ? null : cleanVersion(marketingNoticeTextVersion);
    }

    /** Without the paid-checkout notice — every caller that predates V207, and the native buy screen. */
    public CheckoutConsent(boolean acceptedTerms, String marketingOptInProofText, String marketingOptInTextVersion) {
        this(acceptedTerms, marketingOptInProofText, marketingOptInTextVersion, null, null, false);
    }

    /** Without a text version — every caller that predates V151. */
    public CheckoutConsent(boolean acceptedTerms, String marketingOptInProofText) {
        this(acceptedTerms, marketingOptInProofText, null);
    }

    /** Add the captured fields to a Stripe metadata map. Absent fields are omitted, not sent as "null". */
    public void putInto(Map<String, String> metadata) {
        if (acceptedTerms) metadata.put(META_ACCEPTED_TERMS, "true");
        if (marketingOptInProofText != null) metadata.put(META_MARKETING_PROOF, marketingOptInProofText);
        if (marketingOptInTextVersion != null) metadata.put(META_MARKETING_TEXT_VERSION, marketingOptInTextVersion);
        if (marketingNoticeProofText != null) metadata.put(META_NOTICE_PROOF, marketingNoticeProofText);
        if (marketingNoticeTextVersion != null) metadata.put(META_NOTICE_TEXT_VERSION, marketingNoticeTextVersion);
        if (marketingRefused) metadata.put(META_MARKETING_REFUSED, "true");
    }

    /** Rebuild from Stripe metadata at fulfilment. Missing keys → not captured. */
    public static CheckoutConsent fromMetadata(Map<String, String> metadata) {
        if (metadata == null) return NONE;
        return new CheckoutConsent(
                "true".equals(metadata.get(META_ACCEPTED_TERMS)),
                metadata.get(META_MARKETING_PROOF),
                metadata.get(META_MARKETING_TEXT_VERSION),
                metadata.get(META_NOTICE_PROOF),
                metadata.get(META_NOTICE_TEXT_VERSION),
                "true".equals(metadata.get(META_MARKETING_REFUSED)));
    }

    /**
     * Stamp onto an order. Central so the free and paid paths cannot drift.
     *
     * <p>Only ever writes: a false {@code acceptedTerms} leaves the timestamp
     * null rather than clearing one, because absence means "not recorded" and
     * this object is also the NONE value every legacy caller passes. The notice
     * columns are {@code updatable=false}, so this must run before the first save.
     */
    public void applyTo(Order order) {
        if (acceptedTerms && order.getTermsAcceptedAt() == null) {
            order.setTermsAcceptedAt(Times.nowMicros());
        }
        if (marketingOptInProofText != null) {
            order.setMarketingOptInProof(marketingOptInProofText);
        }
        if (marketingOptInTextVersion != null) {
            order.setMarketingOptInTextVersion(marketingOptInTextVersion);
        }
        if (marketingNoticeProofText != null) {
            order.setMarketingNoticeProof(marketingNoticeProofText);
        }
        if (marketingNoticeTextVersion != null) {
            order.setMarketingNoticeTextVersion(marketingNoticeTextVersion);
        }
        if (marketingRefused) {
            order.setMarketingRefused(true);
        }
    }

    private static String clean(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() > PROOF_MAX ? t.substring(0, PROOF_MAX) : t;
    }

    private static String cleanVersion(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() || t.length() > TEXT_VERSION_MAX ? null : t;
    }
}
