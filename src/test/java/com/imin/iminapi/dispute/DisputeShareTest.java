package com.imin.iminapi.dispute;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-order chargeback split. The €11.49 order is a €10.00 ticket plus imin's booking fee
 * of 5% (50) + €0.99 (99) = 149, as configured in application.yaml.
 */
class DisputeShareTest {

    private static void expect(DisputeShare s, long gross, long fee, long share) {
        assertThat(s.grossWithheldMinor()).as("grossWithheld").isEqualTo(gross);
        assertThat(s.feeShareMinor()).as("feeShare").isEqualTo(fee);
        assertThat(s.organizerShareMinor()).as("organizerShare").isEqualTo(share);
    }

    @Test
    void full_dispute_on_an_11_49_order_costs_the_organizer_its_10_00_ticket() {
        // 1149 withheld; fee round(149 × 1149 / 1149) = 149; share 1149 − 149 = 1000
        expect(DisputeShare.of(1149, 149, 0, 0, 1149), 1149, 149, 1000);
    }

    @Test
    void partial_dispute_takes_the_fee_off_pro_rata() {
        // fee round(298 × 1149 / 2298) = 149; share 1149 − 149 = 1000
        expect(DisputeShare.of(2298, 298, 0, 0, 1149), 1149, 149, 1000);
    }

    @Test
    void partial_dispute_rounds_the_fee_share_half_up() {
        // fee 298 × 500 / 2298 = 64.84 → 65; share 500 − 65 = 435
        expect(DisputeShare.of(2298, 298, 0, 0, 500), 500, 65, 435);
    }

    @Test
    void full_charge_dispute_after_a_refund_withholds_only_what_was_not_refunded() {
        // remaining gross 2298 − 1149 = 1149 caps D 2298; fee round(298 × 1149 / 2298) = 149,
        // remaining fee 298 − 149 = 149; share 1149 − 149 = 1000
        expect(DisputeShare.of(2298, 298, 1149, 149, 2298), 1149, 149, 1000);
    }

    @Test
    void order_with_no_booking_fee_costs_the_whole_disputed_amount() {
        expect(DisputeShare.of(1149, 0, 0, 0, 1149), 1149, 0, 1149);
    }

    @Test
    void fee_share_is_capped_at_the_fee_not_yet_refunded() {
        // proportional fee 149 > remaining fee 149 − 100 = 49; share 1149 − 49 = 1100,
        // capped at the stake 1149 − 49 = 1100 (does not bind below 1100)
        expect(DisputeShare.of(1149, 149, 0, 100, 1149), 1149, 49, 1100);
    }

    @Test
    void share_is_capped_at_the_organizers_remaining_stake() {
        // remaining gross 5349 − 1000 = 4349; fee 349 × 4349 / 5349 = 283.75 → 284;
        // 4349 − 284 = 4065 capped at 4349 − 349 = 4000
        expect(DisputeShare.of(5349, 349, 1000, 0, 5349), 4349, 284, 4000);
    }

    @Test
    void fully_refunded_order_withholds_nothing() {
        expect(DisputeShare.of(1149, 149, 1149, 149, 1149), 0, 0, 0);
    }

    @Test
    void zero_total_order_withholds_nothing() {
        expect(DisputeShare.of(0, 0, 0, 0, 500), 0, 0, 0);
    }

    @Test
    void negative_dispute_amount_withholds_nothing() {
        expect(DisputeShare.of(1149, 149, 0, 0, -10), 0, 0, 0);
    }

    @Test
    void dispute_with_no_order_withholds_its_whole_amount() {
        expect(DisputeShare.unattributed(700), 700, 0, 700);
    }
}
