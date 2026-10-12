package com.imin.iminapi.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ComparablePricesTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                // odd count: the middle of each sorted list
                Arguments.of(List.of(new long[]{2000, 4000}, new long[]{1000, 2000}, new long[]{1500, 2500}),
                        new long[]{1500, 2500}),
                // even count: (1201 + 1500) / 2 = 1350.5 → 1351 half-up; (2400 + 2600) / 2 = 2500
                Arguments.of(List.of(new long[]{1000, 2000}, new long[]{1201, 2400}, new long[]{1500, 2600},
                        new long[]{2000, 3000}), new long[]{1351, 2500}),
                // two events are too few to call a market price
                Arguments.of(List.of(new long[]{1000, 2000}, new long[]{1500, 2500}), null));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void medians_of_per_event_min_and_max(List<long[]> perEvent, long[] expected) {
        var result = ComparablePrices.medians(perEvent);
        if (expected == null) {
            assertThat(result).isEmpty();
        } else {
            assertThat(result).hasValueSatisfying(m -> assertThat(m).containsExactly(expected));
        }
    }
}
