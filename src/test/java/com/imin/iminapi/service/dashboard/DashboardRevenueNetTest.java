package com.imin.iminapi.service.dashboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardRevenueNetTest {

    @Test
    void worked_example_nets_refunds_unrefunded_fee_and_chargebacks() {
        // 12045 gross − 2149 refunded = 9896; − (1045 − 149) fee = 9000; − 5000 = 4000.
        // 5000 = organizer shares 1000 + 4000
        assertThat(DashboardRevenue.net(12_045, 2_149, 1_045, 149, 5_000)).isEqualTo(4_000L);
    }

    @Test
    void refunds_above_gross_clamp_the_refunded_gross_at_zero() {
        assertThat(DashboardRevenue.net(100, 300, 0, 0, 0)).isZero();
    }

    @Test
    void fee_refunds_above_the_fee_clamp_the_fee_at_zero() {
        assertThat(DashboardRevenue.net(1_000, 0, 100, 150, 0)).isEqualTo(1_000L);
    }

    @Test
    void a_negative_result_clamps_at_zero() {
        assertThat(DashboardRevenue.net(1_149, 0, 149, 0, 1_149)).isZero();
    }
}
