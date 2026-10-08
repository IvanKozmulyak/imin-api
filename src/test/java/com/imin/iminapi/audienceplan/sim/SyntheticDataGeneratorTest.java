package com.imin.iminapi.audienceplan.sim;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.Fan;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.SimOrg;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.Target;
import com.imin.iminapi.audienceplan.sim.SyntheticDataGenerator.World;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticDataGeneratorTest {

    private static final AudiencePlanLogic LOGIC = SyntheticDataGenerator.shippedLogic();

    private final SyntheticDataGenerator generator = new SyntheticDataGenerator(LOGIC);

    @Test
    void the_same_seed_gives_the_same_world() {
        assertThat(fingerprint(generator.generate(SyntheticDataGenerator.SEED)))
                .isEqualTo(fingerprint(generator.generate(SyntheticDataGenerator.SEED)));
    }

    @Test
    void every_class_the_priors_know_is_present_with_at_least_ten_fans() {
        Map<String, Integer> counts = SyntheticDataGenerator.classCounts(generator.generate(SyntheticDataGenerator.SEED));
        for (String classKey : LOGIC.priors().classes().keySet()) {
            assertThat(counts.getOrDefault(classKey, 0)).as(classKey).isGreaterThanOrEqualTo(10);
        }
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
