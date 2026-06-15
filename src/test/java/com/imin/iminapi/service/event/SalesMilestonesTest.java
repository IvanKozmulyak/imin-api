package com.imin.iminapi.service.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesMilestonesTest {

    @Test
    void nothingBelowHalf() {
        assertEquals(List.of(), SalesMilestones.satisfied(0, 100));
        assertEquals(List.of(), SalesMilestones.satisfied(49, 100));
    }

    @Test
    void eachThresholdReachedInOrder() {
        assertEquals(List.of(50), SalesMilestones.satisfied(50, 100));
        assertEquals(List.of(50), SalesMilestones.satisfied(79, 100));
        assertEquals(List.of(50, 80), SalesMilestones.satisfied(80, 100));
        assertEquals(List.of(50, 80), SalesMilestones.satisfied(99, 100));
        assertEquals(List.of(50, 80, 100), SalesMilestones.satisfied(100, 100));
    }

    @Test
    void bigJumpReportsEverySatisfiedThreshold() {
        // A single purchase that jumps 0 -> sold out reports all three; the caller
        // records each (so none re-fire) but only notifies on the highest.
        assertEquals(List.of(50, 80, 100), SalesMilestones.satisfied(100, 100));
    }

    @Test
    void floorRoundingOnSmallTiers() {
        assertEquals(List.of(), SalesMilestones.satisfied(1, 3));          // 33%
        assertEquals(List.of(50), SalesMilestones.satisfied(2, 3));        // 66%
        assertEquals(List.of(50, 80, 100), SalesMilestones.satisfied(3, 3)); // 100%
    }

    @Test
    void nonPositiveCapacityIsEmpty() {
        assertEquals(List.of(), SalesMilestones.satisfied(5, 0));
        assertEquals(List.of(), SalesMilestones.satisfied(0, 0));
        assertEquals(List.of(), SalesMilestones.satisfied(3, -1));
    }

    @Test
    void overCapacityStillCapsAtAllThree() {
        // Oversold (the [OVERSOLD] reconciliation path) must not produce phantom thresholds.
        assertEquals(List.of(50, 80, 100), SalesMilestones.satisfied(120, 100));
    }
}
