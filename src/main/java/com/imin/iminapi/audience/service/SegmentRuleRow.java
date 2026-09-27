package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Membership;

import java.util.UUID;

/**
 * The membership columns the segment rule engine reads, so a live COUNT needs no hydrated entities;
 * {@code membershipId} keys the fan-feature and ticket facts in {@link SegmentFacts}.
 */
public record SegmentRuleRow(
        UUID membershipId,
        int events,
        long spendMinor,
        Integer recencyDays,
        int noShow,
        Short nps,
        String lifecycle,
        String consentStatus,
        String consentBasis
) {
    public static SegmentRuleRow of(Membership m) {
        return new SegmentRuleRow(m.getMembershipId(), m.getEvents(), m.getSpendMinor(), m.getRecencyDays(),
                m.getNoShow(), m.getNps(), m.getLifecycle(), m.getConsentStatus(), m.getConsentBasis());
    }
}
