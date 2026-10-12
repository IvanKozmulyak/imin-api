package com.imin.iminapi.cancellation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Worked examples; the booking fee is 5% + €0.99 per ticket, so a €10 ticket carries 50 + 99 = 149. */
class CancellationAmountsTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // case, total, fee, activeRefunded, activeFeeRefunded, refund, bookingFee, ticketPrice
            "'(a) two €10 tickets, nothing refunded', 2298, 298, 0,    0,   2298, 298, 2000",
            "'(b) one of two refunded earlier',       2298, 298, 1149, 149, 1149, 149, 1000",
            "'(c) fee remainder, not a proportion',   1000, 149, 666,  100, 334,  49,  285",
            "'(d) fee already over-refunded',         1000, 149, 666,  150, 334,  0,   334",
            "'(e) fully refunded already',            1149, 149, 1149, 149, 0,    0,   0",
    })
    void owes_the_remainder_with_the_unrefunded_fee(String name, long total, long fee, long active, long activeFee,
                                                    long refund, long bookingFee, long ticketPrice) {
        CancellationAmounts a = CancellationAmounts.of(total, fee, active, activeFee);

        assertThat(a.refundMinor()).isEqualTo(refund);
        assertThat(a.feeMinor()).isEqualTo(bookingFee);
        assertThat(a.ticketPriceMinor()).isEqualTo(ticketPrice);
        assertThat(a.feeMinor() + a.ticketPriceMinor()).isEqualTo(a.refundMinor());
    }
}
