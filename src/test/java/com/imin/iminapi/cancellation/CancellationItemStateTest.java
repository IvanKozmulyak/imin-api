package com.imin.iminapi.cancellation;

import com.imin.iminapi.cancellation.CancellationItemState.Derived;
import com.imin.iminapi.cancellation.CancellationItemState.Facts;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** One row per derive rule, each the minimal facts that reach it; the rule order is load-bearing. */
class CancellationItemStateTest {

    static Stream<Arguments> rules() {
        return Stream.of(
                Arguments.of("other mode", new Facts(false, 2298, true, 0, 0, 2298, false, false, 0),
                        CancellationItemState.OTHER_MODE, null),
                Arguments.of("free order", new Facts(true, 0, false, 0, 0, 0, false, false, 0),
                        CancellationItemState.NOTHING_TO_REFUND, null),
                Arguments.of("paid without a payment", new Facts(true, 2298, false, 0, 0, 2298, false, false, 0),
                        CancellationItemState.NEEDS_ATTENTION, "no_payment"),
                Arguments.of("open dispute", new Facts(true, 2298, true, 1, 0, 2298, false, false, 0),
                        CancellationItemState.WAITING_DISPUTE, null),
                Arguments.of("open dispute beats a lost one", new Facts(true, 2298, true, 1, 2298, 2298, false, false, 0),
                        CancellationItemState.WAITING_DISPUTE, null),
                Arguments.of("lost covers what is owed", new Facts(true, 2298, true, 0, 2298, 2298, false, false, 0),
                        CancellationItemState.DISPUTE_LOST, null),
                Arguments.of("lost covers part", new Facts(true, 2298, true, 0, 1000, 2298, false, false, 0),
                        CancellationItemState.NEEDS_ATTENTION, "lost_partial"),
                Arguments.of("refund in flight", new Facts(true, 2298, true, 0, 0, 0, true, false, 0),
                        CancellationItemState.IN_PROGRESS, null),
                Arguments.of("nothing owed, refund pending", new Facts(true, 2298, true, 0, 0, 0, false, true, 0),
                        CancellationItemState.TRIGGERED, null),
                Arguments.of("nothing owed, settled", new Facts(true, 2298, true, 0, 0, 0, false, false, 0),
                        CancellationItemState.REFUNDED, null),
                Arguments.of("three refusals", new Facts(true, 2298, true, 0, 0, 2298, false, false, 3),
                        CancellationItemState.NEEDS_ATTENTION, "refused"),
                Arguments.of("owes, two refusals so far", new Facts(true, 2298, true, 0, 0, 2298, false, false, 2),
                        CancellationItemState.PENDING, null),
                Arguments.of("owes the remainder", new Facts(true, 2298, true, 0, 0, 1149, false, true, 0),
                        CancellationItemState.PENDING, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void derives(String name, Facts facts, CancellationItemState state, String reason) {
        Derived d = CancellationItemState.derive(facts);

        assertThat(d.state()).isEqualTo(state);
        assertThat(d.reason()).isEqualTo(reason);
    }
}
