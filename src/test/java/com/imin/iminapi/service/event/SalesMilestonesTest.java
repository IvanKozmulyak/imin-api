package com.imin.iminapi.service.event;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesMilestonesTest {

    @ParameterizedTest(name = "{0}/{1} sold → [{2}]")
    @CsvSource({
            // nothing below half
            "0, 100, ''",
            "49, 100, ''",
            // each threshold reached in order; a jump straight to sold out reports all three
            "50, 100, '50'",
            "79, 100, '50'",
            "80, 100, '50 80'",
            "99, 100, '50 80'",
            "100, 100, '50 80 100'",
            // floor rounding on small tiers: 33%, 66%, 100%
            "1, 3, ''",
            "2, 3, '50'",
            "3, 3, '50 80 100'",
            // non-positive capacity
            "5, 0, ''",
            "0, 0, ''",
            "3, -1, ''",
            // oversold (the [OVERSOLD] reconciliation path) must not produce phantom thresholds
            "120, 100, '50 80 100'"
    })
    void satisfied(int sold, int capacity, String thresholds) {
        List<Integer> expected = thresholds.isBlank() ? List.of()
                : Arrays.stream(thresholds.split(" ")).map(Integer::valueOf).toList();
        assertEquals(expected, SalesMilestones.satisfied(sold, capacity));
    }
}
