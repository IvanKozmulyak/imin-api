package com.imin.iminapi.service.dashboard;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Owner of the net-revenue maths; DashboardRevenueTest owns which rows feed it. */
class DashboardRevenueNetTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // 12045 − 2149 refunded = 9896; − (1045 − 149) fee = 9000; − 5000 organizer shares (1000 + 4000) = 4000
            "'worked example nets refunds, unrefunded fee and chargebacks', 12045, 2149, 1045, 149, 5000, 4000",
            "refunds above gross clamp the refunded gross at zero,          100,  300,    0,   0,    0,    0",
            "fee refunds above the fee clamp the fee at zero,              1000,    0,  100, 150,    0, 1000",
            "a negative result clamps at zero,                             1149,    0,  149,   0, 1149,    0"
    })
    void net(String name, long gross, long refunded, long appFee, long appFeeRefunded, long disputedShare,
             long expected) {
        assertThat(DashboardRevenue.net(gross, refunded, appFee, appFeeRefunded, disputedShare)).isEqualTo(expected);
    }
}
