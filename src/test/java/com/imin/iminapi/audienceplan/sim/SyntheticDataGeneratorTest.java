package com.imin.iminapi.audienceplan.sim;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.Fan;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.SimOrg;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.Target;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.World;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;

import static com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.NOW;
import static com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.trueClass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SyntheticDataGeneratorTest {

    private static final AudiencePlanLogic LOGIC = SyntheticDataGenerator.shippedLogic();
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final SyntheticDataGenerator generator = new SyntheticDataGenerator(LOGIC);

    @Test
    void the_same_seed_gives_the_same_world() {
        assertThat(fingerprint(generator.generate(SyntheticDataGenerator.SEED)))
                .isEqualTo(fingerprint(generator.generate(SyntheticDataGenerator.SEED)));
    }

    @Test
    void another_seed_gives_another_world() {
        assertThat(fingerprint(generator.generate(SyntheticDataGenerator.SEED + 1)))
                .isNotEqualTo(fingerprint(generator.generate(SyntheticDataGenerator.SEED)));
    }

    @Test
    void five_orgs_with_two_years_of_ended_events_and_three_targets_four_weeks_out() {
        World world = generator.generate(SyntheticDataGenerator.SEED);
        assertThat(world.orgs()).extracting(o -> o.spec().key()).containsExactly(
                "techno-metz", "afro-paris", "latin-lyon", "party-strasbourg", "coffee-rave-lisbon");
        for (SimOrg org : world.orgs()) {
            int cadence = org.spec().cadenceDays();
            Instant first = org.events().get(0).getStartsAt();
            Instant last = org.events().get(org.events().size() - 1).getStartsAt();
            assertThat(Duration.between(first, NOW).toDays()).isBetween(730L - cadence, 730L);
            assertThat(Duration.between(last, NOW).toDays()).isLessThan(cadence);
            assertThat(org.events()).allMatch(e -> e.getEndsAt().isBefore(NOW));
            assertThat(org.events()).allMatch(e -> LOGIC.genres().whitelist().contains(e.getGenreKey()));
            assertThat(org.targets()).hasSize(3);
            for (Target t : org.targets()) {
                assertThat(t.event().getStartsAt().atZone(org.spec().zone()).toLocalDate())
                        .isEqualTo(NOW.atZone(org.spec().zone()).toLocalDate().plusDays(28));
            }
            assertThat(org.targets().get(0).event().getGenreKey()).isEqualTo(org.spec().homeGenre());
        }
    }

    @Test
    void every_class_the_priors_know_is_present_with_at_least_ten_fans() {
        Map<String, Integer> counts = SyntheticDataGenerator.classCounts(generator.generate(SyntheticDataGenerator.SEED));
        for (String classKey : LOGIC.priors().classes().keySet()) {
            assertThat(counts.getOrDefault(classKey, 0)).as(classKey).isGreaterThanOrEqualTo(10);
        }
    }

    @Test
    void only_fans_with_a_class_get_an_outcome_and_only_buyers_hold_tickets() {
        World world = generator.generate(SyntheticDataGenerator.SEED);
        for (SimOrg org : world.orgs()) {
            for (Target t : org.targets()) {
                for (Fan fan : org.fans()) {
                    var outcome = t.outcomes().get(fan.truth().membershipId());
                    if (SyntheticDataGenerator.NONE.equals(fan.truth().trueClass())) {
                        assertThat(outcome).isNull();
                        continue;
                    }
                    assertThat(outcome).isNotNull();
                    if (outcome.bought()) assertThat(outcome.tickets()).isBetween(1, 3);
                    else assertThat(outcome.tickets()).isZero();
                    if (fan.truth().imported()) assertThat(outcome.trueFit()).isEqualTo(Fit.UNKNOWN);
                }
            }
        }
    }

    @Test
    void mailable_buyers_carry_an_organizer_named_checkout_consent_and_imports_an_import_row_consent() {
        World world = generator.generate(SyntheticDataGenerator.SEED);
        for (SimOrg org : world.orgs()) {
            for (Fan fan : org.fans()) {
                if (fan.truth().imported()) {
                    assertThat(fan.orders()).isEmpty();
                    assertThat(fan.consents()).singleElement().extracting(ConsentRecord::getSource)
                            .isEqualTo("organizer_import_row");
                } else if (fan.truth().mailable() && fan.truth().paidOrders() > 0) {
                    assertThat(fan.consents()).singleElement().satisfies(c -> {
                        assertThat(c.getSource()).isEqualTo("checkout");
                        assertThat(c.getTextVersion()).isIn(LOGIC.logic().legal().organizerNamedTextVersions());
                    });
                } else {
                    assertThat(fan.consents()).isEmpty();
                }
            }
        }
    }

    @Test
    void true_class_follows_the_spec_rules_at_each_boundary() {
        assertThat(trueClass(3, daysAgo(90), PARIS, false)).isEqualTo("loyal");
        assertThat(trueClass(2, daysAgo(90), PARIS, false)).isEqualTo("repeat");
        assertThat(trueClass(1, daysAgo(90), PARIS, false)).isEqualTo("first_timer");
        assertThat(trueClass(3, daysAgo(91), PARIS, false)).isEqualTo("lapsing");
        assertThat(trueClass(1, daysAgo(180), PARIS, false)).isEqualTo("lapsing");
        assertThat(trueClass(5, daysAgo(181), PARIS, false)).isEqualTo("dormant");
        assertThat(trueClass(0, null, PARIS, true)).isEqualTo("imported");
        assertThat(trueClass(0, null, PARIS, false)).isEqualTo("none");
    }

    @Test
    void triangular_draws_stay_in_the_band_with_the_triangular_mean() {
        SplittableRandom rnd = new SplittableRandom(1);
        Band band = new Band(0.12, 0.25, 0.40);
        double sum = 0;
        int n = 50_000;
        for (int i = 0; i < n; i++) {
            double x = SyntheticDataGenerator.triangular(band, rnd);
            assertThat(x).isBetween(0.12, 0.40);
            sum += x;
        }
        assertThat(sum / n).isCloseTo((0.12 + 0.25 + 0.40) / 3, within(0.002));
        assertThat(SyntheticDataGenerator.triangular(new Band(0.35, 0.35, 0.35), rnd)).isEqualTo(0.35);
    }

    @Test
    void tickets_per_order_are_whole_between_one_and_three_with_the_given_mean() {
        SplittableRandom rnd = new SplittableRandom(2);
        long sum = 0;
        int n = 50_000;
        for (int i = 0; i < n; i++) {
            int t = SyntheticDataGenerator.ticketsPerOrder(1.6, rnd);
            assertThat(t).isBetween(1, 3);
            sum += t;
        }
        assertThat((double) sum / n).isCloseTo(1.6, within(0.01));
        assertThat(SyntheticDataGenerator.ticketsPerOrder(1.0, rnd)).isEqualTo(1);
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    /** Everything seed-determined in the world, in a stable order. */
    private static String fingerprint(World world) {
        StringBuilder sb = new StringBuilder();
        for (SimOrg org : world.orgs()) {
            sb.append(org.orgId()).append(org.behaviour()).append('\n');
            for (Event e : org.events()) sb.append(e.getId()).append(e.getGenreKey()).append(e.getStartsAt()).append(';');
            for (Fan f : org.fans()) {
                sb.append('\n').append(f.truth());
                for (Order o : f.orders()) {
                    sb.append(o.getId()).append(o.getPaymentMethod()).append(o.getTotalMinor()).append(o.getCreatedAt());
                }
                for (Ticket t : f.tickets()) sb.append(t.getId()).append(t.getState());
                for (ConsentRecord c : f.consents()) sb.append(c.getSource()).append(c.getOccurredAt());
            }
            for (Target t : org.targets()) {
                sb.append('\n').append(t.event().getId());
                new TreeMap<UUID, Object>(t.outcomes()).forEach((id, o) -> sb.append(id).append(o));
            }
        }
        return sb.toString();
    }
}
