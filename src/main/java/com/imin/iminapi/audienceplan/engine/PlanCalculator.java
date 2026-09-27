package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.Action;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.Timing;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.engine.CoverageVerdict.Coverage;
import com.imin.iminapi.audienceplan.engine.GapCalculator.CountRange;
import com.imin.iminapi.audienceplan.engine.GapCalculator.Gap;
import com.imin.iminapi.audienceplan.engine.GapCalculator.ReachNeeded;
import com.imin.iminapi.audienceplan.engine.ModeSelector.Mode;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Confidence;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One event's audience plan from its tiers, the org's plan-mailable members and the logic files. Pure: the
 * caller loads the inputs and persists the result. Every ticket number is a low / mid / high range.
 */
public final class PlanCalculator {

    /** Ticket counts as shown: each bound rounded once. */
    public record Tickets(int low, int mid, int high) {
        static Tickets of(Band raw) {
            return new Tickets(Rounding.count(raw.low()), Rounding.count(raw.mid()), Rounding.count(raw.high()));
        }
    }

    public record Tier(int quantity, boolean enabled) {}

    /**
     * {@code tribeSize} is the portrait's regulars in the catchment, null until it is known. {@code onSaleAt}
     * may be null. {@code ticketsPerOrder} is the mid assumption, applied to every band.
     */
    public record Input(UUID orgId, String eventGenreKey, List<Tier> tiers, int targetPct, double ticketsPerOrder,
                        Map<String, Integer> consentGateExclusions, List<Person> mailable,
                        Instant now, Instant eventStartsAt, ZoneId eventZone, Instant onSaleAt, CountRange tribeSize,
                        Set<String> excludedClasses, int maxSegments) {

        /** {@code excludedClasses} are the organizer's left-out classes; {@code maxSegments} 0 keeps every segment. */
        public Input {
            excludedClasses = excludedClasses == null ? Set.of() : Set.copyOf(excludedClasses);
            if (maxSegments < 0) throw new IllegalArgumentException("max segments must be >= 0: " + maxSegments);
        }

        /** No organizer overrides: every class, every shown segment. */
        public Input(UUID orgId, String eventGenreKey, List<Tier> tiers, int targetPct, double ticketsPerOrder,
                     Map<String, Integer> consentGateExclusions, List<Person> mailable,
                     Instant now, Instant eventStartsAt, ZoneId eventZone, Instant onSaleAt, CountRange tribeSize) {
            this(orgId, eventGenreKey, tiers, targetPct, ticketsPerOrder, consentGateExclusions, mailable, now,
                    eventStartsAt, eventZone, onSaleAt, tribeSize, Set.of(), 0);
        }
    }

    /** {@code fit} may be {@code UNKNOWN} for members with no taste yet; {@code rawExpected} is unrounded. */
    public record PlanSegment(String classKey, Fit fit, Band rate, Confidence confidence, double ticketsPerOrder,
                              Band rawExpected, Tickets expected, List<UUID> membershipIds) {
        public int mailable() { return membershipIds.size(); }
    }

    public record Assumptions(int targetPct, double ticketsPerOrder) {}

    public record Versions(int logic, int priors) {}

    /**
     * {@code expected} totals are {@code Math.round} of the raw sum over shown segments, not the sum of the
     * rounded segments; coverage and gap come from them. In cold mode {@code expected} and the coverage ratios are
     * null (not computed), never 0. {@code gapExceedsTribe} is null while unknown.
     */
    public record Plan(Mode mode, int capacity, int targetTickets, int mailable, Tickets expected, Coverage coverage,
                       Gap gap, ReachNeeded reachNeeded, Boolean gapExceedsTribe, List<PlanSegment> segments,
                       int smallGroupsNotShown, boolean otherGenreInvited, int otherGenreHeldBack,
                       Map<String, Integer> exclusions, Timing timing, List<Action> actions, Assumptions assumptions,
                       Versions versions) {}

    /** No enabled tier has any places, so there is no target to plan against. */
    public static final class NoCapacityException extends RuntimeException {
        public NoCapacityException() { super("event has no enabled ticket capacity"); }
    }

    private final AudiencePlanLogic logic;
    private final CandidateBuilder builder;

    public PlanCalculator(AudiencePlanLogic logic, ResponseModel model) {
        this.logic = Objects.requireNonNull(logic);
        this.builder = new CandidateBuilder(logic, model);
    }

    /** Σ quantity of enabled tiers. */
    public static int capacity(List<Tier> tiers) {
        long sum = 0;
        for (Tier t : tiers) if (t.enabled() && t.quantity() > 0) sum += t.quantity();
        return (int) Math.min(sum, Integer.MAX_VALUE);
    }

    public static int target(int capacity, int targetPct) {
        if (targetPct < 1 || targetPct > 100) throw new IllegalArgumentException("target pct must be 1..100: " + targetPct);
        return Rounding.percentOf(capacity, targetPct);
    }

    public Plan calculate(Input in) {
        int capacity = capacity(in.tiers());
        if (capacity == 0) throw new NoCapacityException();
        int target = target(capacity, in.targetPct());
        if (target == 0) throw new NoCapacityException();

        // Excluded classes leave the segment pool but stay in the lawful mailable count that sets the mode.
        Set<UUID> distinct = new HashSet<>();
        List<Person> pool = new ArrayList<>();
        int excludedByOrganizer = 0;
        for (Person p : in.mailable()) {
            if (!distinct.add(p.membershipId())) continue;
            if (p.classKey() != null && in.excludedClasses().contains(p.classKey())) excludedByOrganizer++;
            else pool.add(p);
        }
        int mailable = distinct.size();
        Mode mode = ModeSelector.select(mailable, logic.logic().modes());

        List<PlanSegment> segments = new ArrayList<>();
        Tickets expected = null;
        int smallGroups = 0;
        boolean otherInvited = false;
        int otherHeldBack = 0;
        Map<String, Integer> exclusions;
        if (mode == Mode.COLD) {
            // Logic bank 10.7: no segments in cold mode, the whole target is the gap.
            exclusions = Exclusions.withPlanReasons(Exclusions.merge(in.consentGateExclusions(), Map.of()),
                    excludedByOrganizer, 0);
        } else {
            CandidateBuilder.Result built = builder.build(new CandidateBuilder.Input(in.orgId(), in.eventGenreKey(), target,
                    in.ticketsPerOrder(), in.consentGateExclusions(), pool));
            // Segments arrive highest rate first; totals, coverage and invites cover only the kept ones.
            // ponytail: the builder's other-genre gate saw every segment, so a cap can drop coverage it counted.
            List<CandidateBuilder.Segment> kept = built.segments();
            int capped = 0;
            if (in.maxSegments() > 0 && kept.size() > in.maxSegments()) {
                for (CandidateBuilder.Segment s : kept.subList(in.maxSegments(), kept.size())) capped += s.mailable();
                kept = kept.subList(0, in.maxSegments());
            }
            double low = 0, mid = 0, high = 0;
            for (CandidateBuilder.Segment s : kept) {
                Band raw = s.expectedTickets();
                low += raw.low();
                mid += raw.mid();
                high += raw.high();
                segments.add(new PlanSegment(s.classKey(), s.fit(), s.rate(), s.confidence(), in.ticketsPerOrder(), raw,
                        Tickets.of(raw), s.membershipIds()));
            }
            expected = Tickets.of(new Band(low, mid, high));
            smallGroups = built.smallGroupsNotShown();
            otherInvited = built.otherGenreInvited();
            otherHeldBack = built.otherGenreHeldBack();
            exclusions = Exclusions.withPlanReasons(built.exclusions(), excludedByOrganizer, capped);
        }

        Coverage coverage = CoverageVerdict.of(mode, expected, target, logic.logic().coverageVerdict());
        Gap gap = GapCalculator.gap(target, expected);
        Boolean exceedsTribe = GapCalculator.exceedsTribe(gap, in.tribeSize());
        Timing timing = ActionPlanner.timing(in.now(), in.eventStartsAt(), in.eventZone(), in.onSaleAt());
        List<Action> actions = ActionPlanner.plan(coverage.verdict(), segments, timing, exceedsTribe,
                logic.logic().experiments());

        return new Plan(mode, capacity, target, mailable, expected, coverage, gap,
                GapCalculator.reachNeeded(gap, logic.priors()), exceedsTribe, List.copyOf(segments), smallGroups,
                otherInvited, otherHeldBack, exclusions, timing, actions,
                new Assumptions(in.targetPct(), in.ticketsPerOrder()),
                new Versions(logic.logicVersion(), logic.priorsVersion()));
    }
}
