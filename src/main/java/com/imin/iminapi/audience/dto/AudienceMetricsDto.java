package com.imin.iminapi.audience.dto;

import java.util.List;
import java.util.Map;

/**
 * Audience KPIs. The fields after {@code complaintRatePct} come from fan_features and ConsentGate;
 * they are null while the audience plan kill switch is off.
 */
public record AudienceMetricsDto(
        long totalMembers,
        long buyers,
        long prospects,
        long subscribedMailable,
        double subscribedPct,
        List<Integer> listGrowth8w,
        double repeatAttendeePct,
        long explicitConsent,
        long softOptIn,
        /** Unsubscribed recipients / sent recipients of the org's campaigns in percent (0–100), unrounded; null when none were sent. */
        Double unsubRatePct,
        /** Complained recipients / sent-or-delivered recipients of the org's campaigns in percent (0–100), unrounded; null when none were sent. */
        Double complaintRatePct,
        /** Memberships created in the last 30 days. */
        Long newLast30Days,
        /** Redeemed / countable tickets of paid orders for this org's ended events. */
        RateRange showedUpPct,
        /** Members with 2+ paid orders / members with 1+. */
        RateRange cameBackPct,
        /** Members ConsentGate lets the plan email: the same count the audience plan uses. */
        Integer mailable,
        /** Members whose only consent is legacy and unproven (ConsentGate reason legacy_unproven). */
        Integer legacyNotMailable,
        /** Mailable members by the basis on their membership (explicit, soft_opt_in). */
        Map<String, Integer> mailableByBasis,
        /** Every ConsentGate exclusion reason with its count; these plus mailable sum to the member count. */
        Map<String, Integer> exclusions,
        /** Members per guest class; a member without computed features counts as none. */
        Map<String, Long> classCounts,
        /** Mean taste over the 8 genre buckets; null when no member has taste yet. */
        Map<String, Double> tasteShares,
        /** Members with a non-empty taste, the base of tasteShares. */
        Long tasteMembers
) {

    /** The pre-existing KPIs with every fan-feature field null. */
    public static AudienceMetricsDto base(long totalMembers, long buyers, long prospects, long subscribedMailable,
                                          double subscribedPct, List<Integer> listGrowth8w, double repeatAttendeePct,
                                          long explicitConsent, long softOptIn, Double unsubRatePct,
                                          Double complaintRatePct) {
        return new AudienceMetricsDto(totalMembers, buyers, prospects, subscribedMailable, subscribedPct,
                listGrowth8w, repeatAttendeePct, explicitConsent, softOptIn, unsubRatePct, complaintRatePct,
                null, null, null, null, null, null, null, null, null, null);
    }
}
