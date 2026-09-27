package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.service.ExperimentService.Split;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The deterministic holdout and arm split; no Spring, no database. */
class ExperimentServiceTest {

    private static final List<String> TWO_ARMS = List.of("launch", "d3");

    @Test
    void sameSeed_givesTheSameAssignment() {
        List<UUID> ids = ids(235);
        Split a = ExperimentService.split(ids, 42L, 15, 60, TWO_ARMS);
        Split b = ExperimentService.split(ids, 42L, 15, 60, TWO_ARMS);
        assertThat(b).isEqualTo(a);
    }

    @Test
    void anotherSeed_givesAnotherAssignment() {
        List<UUID> ids = ids(235);
        assertThat(ExperimentService.split(ids, 1L, 15, 60, TWO_ARMS).holdout())
                .isNotEqualTo(ExperimentService.split(ids, 2L, 15, 60, TWO_ARMS).holdout());
    }

    @Test
    void inputOrder_doesNotMatter_membersAreSortedFirst() {
        List<UUID> ids = ids(100);
        List<UUID> shuffled = new ArrayList<>(ids);
        Collections.reverse(shuffled);
        assertThat(ExperimentService.split(shuffled, 7L, 15, 60, TWO_ARMS))
                .isEqualTo(ExperimentService.split(ids, 7L, 15, 60, TWO_ARMS));
    }

    @Test
    void firstTimerFixture_235_is35_100_100() {
        Split s = ExperimentService.split(ids(235), 9L, 15, 60, TWO_ARMS);
        assertThat(s.holdout()).hasSize(35);
        assertThat(s.arms().get("launch")).hasSize(100);
        assertThat(s.arms().get("d3")).hasSize(100);
        assertCompleteAndDisjoint(s, 235);
    }

    @Test
    void loyalFixture_40_hasNoHoldout_20_20() {
        Split s = ExperimentService.split(ids(40), 9L, 15, 60, TWO_ARMS);
        assertThat(s.holdout()).isEmpty();
        assertThat(s.arms().get("launch")).hasSize(20);
        assertThat(s.arms().get("d3")).hasSize(20);
    }

    @Test
    void holdoutStartsAtSixtyMembers() {
        assertThat(ExperimentService.split(ids(59), 3L, 15, 60, TWO_ARMS).holdout()).isEmpty();
        assertThat(ExperimentService.split(ids(60), 3L, 15, 60, TWO_ARMS).holdout()).hasSize(9);
    }

    @Test
    void thousandMembers_holdOut150() {
        Split s = ExperimentService.split(ids(1000), 2026L, 15, 60, TWO_ARMS);
        assertThat(s.holdout()).hasSize(150);
        assertCompleteAndDisjoint(s, 1000);
    }

    @Test
    void holdoutPct_isFloored() {
        // 61 × 20 / 100 = 12.2
        assertThat(ExperimentService.split(ids(61), 3L, 20, 60, TWO_ARMS).holdout()).hasSize(12);
    }

    @Test
    void anOddRemainder_goesToTheFirstArm() {
        Split s = ExperimentService.split(ids(41), 5L, 15, 60, TWO_ARMS);
        assertThat(s.arms().get("launch")).hasSize(21);
        assertThat(s.arms().get("d3")).hasSize(20);
    }

    @Test
    void oneArm_takesEveryoneOutsideTheHoldout() {
        Split s = ExperimentService.split(ids(100), 5L, 10, 60, List.of("d3"));
        assertThat(s.holdout()).hasSize(10);
        assertThat(s.arms()).containsOnlyKeys("d3");
        assertThat(s.arms().get("d3")).hasSize(90);
    }

    @Test
    void armsKeepTheRequestedOrder() {
        Split s = ExperimentService.split(ids(10), 5L, 15, 60, List.of("d3", "launch"));
        assertThat(s.arms().keySet()).containsExactly("d3", "launch");
    }

    @Test
    void noArms_isRefused() {
        assertThatThrownBy(() -> ExperimentService.split(ids(10), 5L, 15, 60, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theShuffleActuallyMovesMembers() {
        List<UUID> sorted = ids(200).stream().sorted().toList();
        Split s = ExperimentService.split(sorted, 11L, 15, 60, List.of("launch"));
        List<UUID> order = new ArrayList<>(s.holdout());
        order.addAll(s.arms().get("launch"));
        assertThat(order).isNotEqualTo(sorted);
    }

    private static void assertCompleteAndDisjoint(Split s, int n) {
        Set<UUID> all = new HashSet<>(s.holdout());
        int total = s.holdout().size();
        for (List<UUID> arm : s.arms().values()) {
            all.addAll(arm);
            total += arm.size();
        }
        assertThat(total).isEqualTo(n);
        assertThat(all).hasSize(n);
    }

    private static List<UUID> ids(int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(UUID.randomUUID());
        return out;
    }
}
