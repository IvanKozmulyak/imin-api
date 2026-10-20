package com.imin.iminapi.service.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** "Ticket sales from this email": the order count beside the revenue it was built from. */
class NetOrderRevenueTest {

    /** Rows are [key, total, succeeded refunds, lost chargebacks], in minor units. */
    @Test
    void keptOrdersByKey_workedExample() {
        List<Object[]> rows = List.of(
                new Object[]{"A", 1000L, 0L, 0L},      // kept: 1000
                new Object[]{"A", 1000L, 1000L, 0L},   // refunded in full: 0, not an order
                new Object[]{"A", 0L, 0L, 0L},         // free order: 0, still an order
                new Object[]{"A", 1000L, 400L, 0L},    // part refund: 600
                new Object[]{"B", 500L, 0L, 500L});    // chargeback lost in full: 0, not an order

        assertThat(NetOrderRevenue.keptOrdersByKey(rows)).containsOnlyKeys("A", "B")
                .containsEntry("A", 3L).containsEntry("B", 0L);
    }
}
