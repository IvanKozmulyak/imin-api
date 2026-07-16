package com.imin.iminapi.model;

import java.util.Map;

/**
 * Last-touch attribution the buyer's browser carried into checkout (V62): the landing
 * {@code utm_*} params plus the {@code /track} beacon's per-session {@code anon_id}.
 *
 * <p>Grouping these four correlated, always-travel-together fields into one value object
 * keeps the checkout signatures readable — they ride the SAME proven path as
 * {@code marketingOptIn}/{@code adsConsent} (V61): public checkout request → Stripe
 * session + PaymentIntent metadata → {@code Order} at fulfilment.
 *
 * <p>Every field is nullable: an organic buyer arrives with no tags at all, and the FE
 * omits {@code anonId} when sessionStorage is unavailable (private mode). {@link #NONE}
 * is the no-attribution value used by the older checkout overloads and internal callers.
 *
 * <p>Values are capped to the column widths at construction ({@code utm_*} 128,
 * {@code anon_id} 64 — mirroring {@code event_funnel_events}) and blank is normalized to
 * null, so an over-long or empty inbound param can never fail the insert or masquerade as
 * a real tag.
 */
public record CheckoutAttribution(String utmSource, String utmMedium, String utmCampaign, String anonId) {

    /** Metadata keys used on the Stripe Session/PaymentIntent. */
    public static final String META_UTM_SOURCE = "utm_source";
    public static final String META_UTM_MEDIUM = "utm_medium";
    public static final String META_UTM_CAMPAIGN = "utm_campaign";
    public static final String META_ANON_ID = "anon_id";

    private static final int UTM_MAX = 128;
    private static final int ANON_ID_MAX = 64;

    /** No attribution captured — the value for internal callers and untagged flows. */
    public static final CheckoutAttribution NONE = new CheckoutAttribution(null, null, null, null);

    public CheckoutAttribution {
        utmSource = clean(utmSource, UTM_MAX);
        utmMedium = clean(utmMedium, UTM_MAX);
        utmCampaign = clean(utmCampaign, UTM_MAX);
        anonId = clean(anonId, ANON_ID_MAX);
    }

    /** True when nothing at all was captured — lets callers skip writing empty metadata. */
    public boolean isEmpty() {
        return utmSource == null && utmMedium == null && utmCampaign == null && anonId == null;
    }

    /** Add the non-null fields to a Stripe metadata map. Absent fields are omitted, not sent as "null". */
    public void putInto(Map<String, String> metadata) {
        if (utmSource != null) metadata.put(META_UTM_SOURCE, utmSource);
        if (utmMedium != null) metadata.put(META_UTM_MEDIUM, utmMedium);
        if (utmCampaign != null) metadata.put(META_UTM_CAMPAIGN, utmCampaign);
        if (anonId != null) metadata.put(META_ANON_ID, anonId);
    }

    /** Rebuild from Stripe metadata at fulfilment. Missing keys → null (pre-V62 sessions in flight). */
    public static CheckoutAttribution fromMetadata(Map<String, String> metadata) {
        if (metadata == null) return NONE;
        return new CheckoutAttribution(
                metadata.get(META_UTM_SOURCE),
                metadata.get(META_UTM_MEDIUM),
                metadata.get(META_UTM_CAMPAIGN),
                metadata.get(META_ANON_ID));
    }

    /** Stamp the attribution onto an order. Central so free + paid paths can't drift. */
    public void applyTo(Order order) {
        order.setUtmSource(utmSource);
        order.setUtmMedium(utmMedium);
        order.setUtmCampaign(utmCampaign);
        order.setAnonId(anonId);
    }

    private static String clean(String v, int max) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
