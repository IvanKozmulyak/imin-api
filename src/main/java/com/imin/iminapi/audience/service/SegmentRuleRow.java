package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Membership;

/**
 * The eight membership columns the segment rule engine actually reads.
 *
 * <p>Exists so a segment's live COUNT can be computed without hydrating the whole
 * memberships table as entities: {@code GET /audience/segments} resolves every segment on
 * every call, so an org with N custom segments used to materialize N full copies of its
 * audience per dashboard load. The rule engine takes this shape, and a Membership converts
 * to it for free on the paths that genuinely need the entities.
 */
public record SegmentRuleRow(
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
        return new SegmentRuleRow(m.getEvents(), m.getSpendMinor(), m.getRecencyDays(),
                m.getNoShow(), m.getNps(), m.getLifecycle(), m.getConsentStatus(), m.getConsentBasis());
    }
}
