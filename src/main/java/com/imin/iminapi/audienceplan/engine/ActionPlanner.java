package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Experiments;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.TimingArm;
import com.imin.iminapi.audienceplan.engine.CoverageVerdict.Verdict;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.PlanSegment;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rule-based next steps dated in the event timezone; no door QR or survey actions until their backends exist.
 * "Enough days to close the gap" needs a sales-velocity model, so v1 reports daysToEvent and drops passed arms.
 */
public final class ActionPlanner {

    /** The D-3 arm goes out this many days before the event date. */
    public static final int D3_DAYS_BEFORE = 3;

    public enum ActionType { INVITE, IMPORT_WITH_PROOF, RETHINK_TARGET }

    /** Logic 7.5: the ways to rethink a target the tribe cannot fill, as copy keys for the UI. */
    public static final List<String> RETHINK_TARGET_OPTIONS = List.of("smaller_room", "other_date", "stronger_lineup");

    public record ArmDate(TimingArm arm, LocalDate date) {}

    /** Only {@code INVITE} names a segment, arms and a holdout share; the others carry nulls and no arms. */
    public record Action(ActionType type, String classKey, Fit fit, List<ArmDate> arms, Integer holdoutPct) {
        static Action of(ActionType type) { return new Action(type, null, null, List.of(), null); }
    }

    /**
     * Calendar dates in the event timezone; launch is the on-sale date when still ahead, else today.
     * {@code eventStarted} is true once now is at or after the event start instant.
     */
    public record Timing(LocalDate today, LocalDate eventDate, LocalDate launchDate, boolean eventStarted) {
        /** Logic 0.2: whole days from today to the event date (negative once it has passed). */
        public int daysToEvent() { return (int) ChronoUnit.DAYS.between(today, eventDate); }

        /** The D-3 date, or null when it is not after the launch date (today or past with launch = today). */
        public LocalDate d3Date() {
            LocalDate d3 = eventDate.minusDays(D3_DAYS_BEFORE);
            return d3.isAfter(launchDate) ? d3 : null;
        }
    }

    private ActionPlanner() {}

    public static Timing timing(Instant now, Instant eventStartsAt, ZoneId eventZone, Instant onSaleAt) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(eventStartsAt, "eventStartsAt");
        Objects.requireNonNull(eventZone, "eventZone");
        LocalDate today = LocalDate.ofInstant(now, eventZone);
        LocalDate launch = today;
        if (onSaleAt != null) {
            LocalDate onSale = LocalDate.ofInstant(onSaleAt, eventZone);
            if (onSale.isAfter(today)) launch = onSale;
        }
        return new Timing(today, LocalDate.ofInstant(eventStartsAt, eventZone), launch, !now.isBefore(eventStartsAt));
    }

    public static List<Action> plan(Verdict verdict, List<PlanSegment> segments, Timing timing, Boolean gapExceedsTribe,
                                    Experiments experiments) {
        List<Action> out = new ArrayList<>();
        // No invite once the event has passed or, on its day, has already started.
        if (timing.daysToEvent() >= 0 && !timing.eventStarted()) {
            for (PlanSegment s : segments) {
                List<ArmDate> arms = arms(timing, experiments.defaultTimingArms());
                if (arms.isEmpty()) continue;
                int holdout = s.mailable() >= experiments.holdoutMinMailable() ? experiments.holdoutPct() : 0;
                out.add(new Action(ActionType.INVITE, s.classKey(), s.fit(), arms, holdout));
            }
        }
        if (verdict == Verdict.WEAK || verdict == Verdict.COLD) out.add(Action.of(ActionType.IMPORT_WITH_PROOF));
        if (Boolean.TRUE.equals(gapExceedsTribe)) out.add(Action.of(ActionType.RETHINK_TARGET));
        return List.copyOf(out);
    }

    /**
     * At most {@code max} steps: every non-invite action first claims a slot, the rest go to invites in segment
     * order; the kept actions keep their original order.
     */
    public static List<Action> topSteps(List<Action> actions, int max) {
        if (max < 0) throw new IllegalArgumentException("max must be >= 0: " + max);
        if (actions.size() <= max) return List.copyOf(actions);
        int otherSlots = (int) Math.min(max, actions.stream().filter(a -> a.type() != ActionType.INVITE).count());
        int inviteSlots = max - otherSlots;
        List<Action> out = new ArrayList<>();
        int invites = 0;
        int kept = 0;
        for (Action a : actions) {
            if (a.type() == ActionType.INVITE) {
                if (invites < inviteSlots) {
                    out.add(a);
                    invites++;
                }
            } else if (kept < otherSlots) {
                out.add(a);
                kept++;
            }
        }
        return List.copyOf(out);
    }

    private static List<ArmDate> arms(Timing timing, List<TimingArm> configured) {
        List<ArmDate> arms = new ArrayList<>();
        for (TimingArm arm : configured) {
            LocalDate date = switch (arm) {
                case LAUNCH -> timing.launchDate().isAfter(timing.eventDate()) ? null : timing.launchDate();
                case D3 -> timing.d3Date();
                // Timed from tiers or Momentum when the draft is approved, never suggested by the plan.
                case EARLY_BIRD_END, SLUMP -> null;
            };
            if (date != null) arms.add(new ArmDate(arm, date));
        }
        return List.copyOf(arms);
    }
}
