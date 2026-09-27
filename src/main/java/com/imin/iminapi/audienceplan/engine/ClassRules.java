package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.ClassRule;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Guest class from the ordered class rules of the logic file; first match wins, else {@link #NONE}. */
public final class ClassRules {

    public static final String NONE = "none";
    static final String STATUS_SUBSCRIBED = "subscribed";
    static final String BASIS_EXPLICIT = "explicit";
    static final String CHANNEL_EMAIL = "email";
    static final String SOURCE_IMPORT_ROW = "organizer_import_row";

    private ClassRules() {}

    /**
     * True iff the latest subscribing email consent record is explicit with source {@code organizer_import_row}.
     * Only the per-row import writes that source, so this is false for every legacy bulk import.
     * A sign-up still awaiting confirmation is not a basis, so it cannot hide the import either.
     */
    public static boolean importBasisValid(Collection<ConsentRecord> consents) {
        return consents.stream()
                .filter(c -> CHANNEL_EMAIL.equals(c.getChannel()))
                .filter(c -> STATUS_SUBSCRIBED.equals(c.getStatus()))
                .filter(c -> !c.isAwaitingConfirmation())
                .max(Comparator.comparing(ConsentRecord::getOccurredAt))
                .map(c -> BASIS_EXPLICIT.equals(c.getLawfulBasis()) && SOURCE_IMPORT_ROW.equals(c.getSource()))
                .orElse(false);
    }

    /** A rule with a day bound never matches when that day count is unknown (null). */
    public static String classify(List<ClassRule> rules, int paidOrders, Integer daysSinceLastPaid,
                                  Integer daysSinceLastContact, boolean importBasisValid) {
        for (ClassRule r : rules) {
            if (!within(paidOrders, r.paidOrdersMin(), r.paidOrdersMax())) continue;
            if (!within(daysSinceLastPaid, r.daysSinceLastPaidMin(), r.daysSinceLastPaidMax())) continue;
            if (!within(daysSinceLastContact, null, r.daysSinceLastContactMax())) continue;
            if (r.requiresImportBasis() && !importBasisValid) continue;
            return r.key();
        }
        return NONE;
    }

    private static boolean within(Integer value, Integer min, Integer max) {
        if (min == null && max == null) return true;
        if (value == null) return false;
        return (min == null || value >= min) && (max == null || value <= max);
    }
}
