package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.MetaAds;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Priors;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tickets;

/** New people still needed after the list (logic bank block 7), and how many to reach per channel. */
public final class GapCalculator {

    /** Tickets still to sell from outside the list; both bounds from the rounded expected totals, never negative. */
    public record Gap(int low, int high) {}

    /** An integer range; a null bound is unknown and renders as {@code ?}, never as 0. */
    public record CountRange(Integer low, Integer high) {}

    public enum ReachStatus { ESTIMATED, UNVERIFIED, UNKNOWN }

    /** People to reach on one channel; bounds are null unless {@code ESTIMATED}. */
    public record Reach(ReachStatus status, Integer low, Integer high) {
        static final Reach UNVERIFIED = new Reach(ReachStatus.UNVERIFIED, null, null);
        static final Reach UNKNOWN = new Reach(ReachStatus.UNKNOWN, null, null);
    }

    public record ReachNeeded(Reach metaAds, Reach instagramOrganic) {}

    private GapCalculator() {}

    /** {@code expected} is null in cold mode, where the whole target is the gap. */
    public static Gap gap(int targetTickets, Tickets expected) {
        if (expected == null) return new Gap(targetTickets, targetTickets);
        return new Gap(Math.max(0, targetTickets - expected.high()), Math.max(0, targetTickets - expected.low()));
    }

    /** Meta stays unverified while its prior is flagged so; Instagram organic has no evidence and stays unknown. */
    public static ReachNeeded reachNeeded(Gap gap, Priors priors) {
        MetaAds meta = priors.metaAds();
        Reach metaAds = meta.verified()
                ? reach(gap, new Band(meta.ctr() * meta.landingToTicket().low(), meta.ctr() * meta.landingToTicket().mid(),
                        meta.ctr() * meta.landingToTicket().high()))
                : Reach.UNVERIFIED;
        Reach instagram = priors.instagramOrganic().map(rate -> reach(gap, rate)).orElse(Reach.UNKNOWN);
        return new ReachNeeded(metaAds, instagram);
    }

    /** Fewest people at the best rate for the small gap, most at the worst rate for the large one. */
    static Reach reach(Gap gap, Band reachToTicket) {
        return new Reach(ReachStatus.ESTIMATED, divide(gap.low(), reachToTicket.high()), divide(gap.high(), reachToTicket.low()));
    }

    /** Null when the rate is zero or the people count would not fit an int (a near-zero rate). */
    private static Integer divide(int tickets, double rate) {
        if (tickets == 0) return 0;
        if (!(rate > 0)) return null;
        double people = tickets / rate;
        if (!(people <= Integer.MAX_VALUE)) return null;
        return Rounding.count(people);
    }

    /** Logic 7.5: null while the tribe size is unknown; true when even the small gap exceeds the largest tribe. */
    public static Boolean exceedsTribe(Gap gap, CountRange tribeSize) {
        if (tribeSize == null || tribeSize.high() == null) return null;
        return gap.low() > tribeSize.high();
    }
}
