package com.imin.iminapi.audienceplan.sim;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.engine.CalibrationSource;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.engine.FanFeatureCalculator;
import com.imin.iminapi.audienceplan.engine.ModeSelector.Mode;
import com.imin.iminapi.audienceplan.engine.PlanCalculator;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Plan;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.PlanSegment;
import com.imin.iminapi.audienceplan.engine.ResponseModel;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.Fan;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.Outcome;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.SimOrg;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.Target;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Spec §14.4: the plan engine on a synthetic world with known truth. It proves the code under the priors,
 * not the real world, since the world's behaviour is drawn from the same priors.
 */
class SimulatorEvalTest {

    private static final Logger log = LoggerFactory.getLogger(SimulatorEvalTest.class);

    private static final AudiencePlanLogic LOGIC = SyntheticDataGenerator.shippedLogic();
    private static final double MIN_TRUTH_IN_RANGE = 0.80;
    private static final int TARGET_PCT = 85;
    private static final String NOT_MAILABLE_REASON = "no_basis";

    /**
     * One shown segment of one plan. {@code trueExpected} is Σ true rate × the org's true tickets per order over its
     * members (the known truth the band claims to cover); {@code bought} is the tickets they bought in one draw.
     */
    record SegmentCheck(String org, String targetGenre, PlanSegment segment, double trueExpected, int bought) {
        boolean truthInRange() {
            return inShown(Math.round(trueExpected));
        }

        boolean boughtInRange() {
            return inShown(bought);
        }

        private boolean inShown(long tickets) {
            return tickets >= segment.expected().low() && tickets <= segment.expected().high();
        }

        @Override
        public String toString() {
            return "%s/%s %s/%s n=%d shown=%d-%d truth=%.1f bought=%d".formatted(org, targetGenre,
                    segment.classKey(), segment.fit(), segment.mailable(), segment.expected().low(),
                    segment.expected().high(), trueExpected, bought);
        }
    }

    private static World world;
    private static Map<UUID, Map<UUID, FanFeatureCalculator.Result>> featuresByOrg;
    private static List<SegmentCheck> checks;
    private static List<Plan> plans;

    @BeforeAll
    static void simulate() {
        world = new SyntheticDataGenerator(LOGIC).generate(SyntheticDataGenerator.SEED);
        FanFeatureCalculator features = new FanFeatureCalculator(LOGIC);
        PlanCalculator calculator = new PlanCalculator(LOGIC, new ResponseModel(LOGIC, CalibrationSource.NONE));
        Clock clock = Clock.fixed(world.now(), ZoneOffset.UTC);
        double ticketsPerOrder = LOGIC.priors().ticketsPerOrder().mid();

        featuresByOrg = new LinkedHashMap<>();
        checks = new ArrayList<>();
        plans = new ArrayList<>();
        for (SimOrg org : world.orgs()) {
            Map<UUID, FanFeatureCalculator.Result> byFan = new LinkedHashMap<>();
            for (Fan fan : org.fans()) {
                byFan.put(fan.truth().membershipId(), features.calculate(new FanFeatureCalculator.Input(org.orgId(),
                        org.spec().zone(), false, fan.orders(), fan.tickets(), org.events(), fan.consents(),
                        List.of()), clock));
            }
            featuresByOrg.put(org.orgId(), byFan);

            List<Person> mailable = new ArrayList<>();
            int notMailable = 0;
            for (Fan fan : org.fans()) {
                if (!fan.truth().mailable()) {
                    notMailable++;
                    continue;
                }
                FanFeatureCalculator.Result f = byFan.get(fan.truth().membershipId());
                mailable.add(new Person(fan.truth().membershipId(), f.fanClass(), f.taste(), f.noShowN(),
                        false, false, 0, 0));
            }

            for (Target target : org.targets()) {
                Plan plan = calculator.calculate(new PlanCalculator.Input(org.orgId(), target.event().getGenreKey(),
                        target.tiers(), TARGET_PCT, ticketsPerOrder, Map.of(NOT_MAILABLE_REASON, notMailable),
                        mailable, world.now(), target.event().getStartsAt(), org.spec().zone(), null, null));
                plans.add(plan);
                for (PlanSegment s : plan.segments()) {
                    double trueExpected = 0;
                    int bought = 0;
                    for (UUID id : s.membershipIds()) {
                        Outcome o = target.outcomes().get(id);
                        if (o == null) fail("segment member without a simulated outcome: " + id);
                        trueExpected += o.rate() * org.behaviour().ticketsPerOrder();
                        bought += o.tickets();
                    }
                    checks.add(new SegmentCheck(org.spec().key(), target.event().getGenreKey(), s, trueExpected,
                            bought));
                }
            }
        }
    }

    @Test
    void the_calculator_recovers_every_fans_true_class_no_shows_and_paid_orders() {
        for (SimOrg org : world.orgs()) {
            Map<UUID, FanFeatureCalculator.Result> byFan = featuresByOrg.get(org.orgId());
            for (Fan fan : org.fans()) {
                FanFeatureCalculator.Result f = byFan.get(fan.truth().membershipId());
                assertThat(f.fanClass()).as("class of %s", fan.truth()).isEqualTo(fan.truth().trueClass());
                assertThat(f.noShowN()).as("no-shows of %s", fan.truth()).isEqualTo(fan.truth().noShowN());
                assertThat(f.paidOrders()).as("paid orders of %s", fan.truth()).isEqualTo(fan.truth().paidOrders());
            }
        }
    }

    @Test
    void every_plan_is_warm_or_hot_and_shows_segments() {
        assertThat(plans).hasSize(world.orgs().size() * 3);
        for (Plan p : plans) {
            assertThat(p.mode()).isNotEqualTo(Mode.COLD);
            assertThat(p.segments()).isNotEmpty();
        }
    }

    @Test
    void true_expected_tickets_fall_inside_the_shown_range_for_at_least_80_percent_of_segments() {
        List<SegmentCheck> misses = checks.stream().filter(c -> !c.truthInRange()).toList();
        double inRange = 1.0 - (double) misses.size() / checks.size();
        log.info("Simulator calibration: truth inside the shown range for {} of {} segments ({}%)",
                checks.size() - misses.size(), checks.size(), Math.round(inRange * 1000) / 10.0);
        misses.forEach(m -> log.info("  truth outside: {}", m));

        assertThat(checks).as("enough segments for the fraction to mean something").hasSizeGreaterThanOrEqualTo(30);
        assertThat(inRange).as("share of segments with truth inside the range; misses: %s", misses)
                .isGreaterThanOrEqualTo(MIN_TRUTH_IN_RANGE);
    }

    @Test
    void tickets_bought_in_one_draw_are_reported_against_the_shown_range() {
        long inRange = checks.stream().filter(SegmentCheck::boughtInRange).count();
        // The range bands the rate, not one draw's noise; 60% floor sits below the 70-81% seen across seeds.
        log.info("Simulator single draw: bought tickets inside the shown range for {} of {} segments ({}%)",
                inRange, checks.size(), Math.round(1000.0 * inRange / checks.size()) / 10.0);
        assertThat((double) inRange / checks.size()).as("single-draw share inside the shown range")
                .isGreaterThanOrEqualTo(0.60);
    }
}
