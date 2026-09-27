package com.imin.iminapi.audienceplan.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The audience plan's logic, priors and genre files as loaded and validated by {@link LogicLoader}.
 * Every rate is a {@link Band}; an unknown value is {@link Optional#empty()}, never 0.
 */
public record AudiencePlanLogic(Logic logic, Priors priors, Genres genres) {

    public int logicVersion() { return logic.version(); }
    public int priorsVersion() { return priors.version(); }

    /** A low / mid / high triple with {@code low <= mid <= high}. */
    public record Band(double low, double mid, double high) {}

    public record Logic(
            int version,
            Modes modes,
            int targetDefaultPct,
            int minSegmentToShow,
            int tasteHalfLifeDays,
            List<ClassRule> classes,
            double inviteOtherGenreOnlyIfCoverageBelow,
            List<Exclusion> exclusions,
            CoverageVerdict coverageVerdict,
            Experiments experiments,
            Legal legal) {}

    public record Modes(int warmMinMailable, int hotMinMailable) {}

    /** Bounds are inclusive; a null bound means no bound. Classes apply in list order, first match. */
    public record ClassRule(
            String key,
            Integer paidOrdersMin,
            Integer paidOrdersMax,
            Integer daysSinceLastPaidMin,
            Integer daysSinceLastPaidMax,
            Integer daysSinceLastContactMax,
            boolean requiresImportBasis) {}

    public record CoverageVerdict(BandPoint verdictOn, double strong, double medium) {}

    /** Which point of a band a rule reads. */
    public enum BandPoint { LOW, MID, HIGH }

    public enum Exclusion {
        CONSENT_GATE_FALSE, BOUGHT_THIS_EVENT, EMAILED_LAST_48H, SENDS_THIS_EVENT_GTE_2, SENDS_30D_GTE_4
    }

    public record Experiments(int holdoutPct, int holdoutMinMailable, List<TimingArm> defaultTimingArms) {}

    public enum TimingArm { LAUNCH, D3 }

    public record Legal(
            int retentionDays,
            boolean softOptInRequiresPaidOrderAndOrgSeller,
            boolean esRobinsonCheck,
            Map<String, ProofRequirement> explicitSources,
            Set<String> organizerNamedTextVersions) {}

    /** What a consent source needs before it counts as explicit. */
    public enum ProofRequirement { ORGANIZER_NAMED_TEXT_VERSION, PROVENANCE_ROW, TEXT_VERSION }

    public record Priors(
            int version,
            Map<String, ClassPrior> classes,
            int priorStrengthInvitations,
            GenreFit genreFit,
            Band noShowBefore,
            Band noShowShowUpIfBuy,
            Band ticketsPerOrder,
            Band showUpPaid,
            Band showUpFreeRsvp,
            MetaAds metaAds,
            Optional<Band> instagramOrganic,
            Map<String, SourcedRate> tribeSize) {}

    public record ClassPrior(Band purchaseRate, Band unsubPerSend) {}

    /** {@code unknown} applies to members with no taste yet; it is validated as {@code > 0}. */
    public record GenreFit(double same, double adjacent, double other, double unknown) {}

    public record MetaAds(double ctr, Band landingToTicket, boolean verified, String note) {}

    /**
     * A research rate range with its source and year. A derived rate is our own computation from the source:
     * it has no year and carries a note instead.
     */
    public record SourcedRate(double low, double high, String source, Integer year, boolean derived, String note) {}

    /** {@code adjacency} is symmetric: each key maps to its neighbours. */
    public record Genres(
            int version,
            List<String> whitelist,
            Map<String, Set<String>> adjacency,
            List<String> forbiddenTerms) {}
}
