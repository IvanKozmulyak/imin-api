package com.imin.iminapi.settlement;

import com.imin.iminapi.dispute.DisputeShare;
import com.imin.iminapi.model.Order;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Worked numbers from the 2026-10-05 sandbox charge: 1149 USD with fee 149 settled as a 1025 EUR transfer
 * and a 133 EUR fee balance transaction (rate 0.892011).
 */
class SettlementRateTest {

    private static final SettlementRate USD = new SettlementRate(1_149, 149, 1_025, 133);

    @Test
    void eur_order_converts_to_itself() {
        SettlementRate eur = new SettlementRate(1_149, 149, 1_149, 149);
        assertThat(eur.gross(574)).isEqualTo(574L);
        assertThat(eur.fee(74)).isEqualTo(74L);
        assertThat(eur.stake(574, 74)).isEqualTo(500L);
    }

    @Test
    void usd_order_converts_at_its_own_stripe_ratio() {
        // ⌊(2·574·1025 + 1149) / 2298⌋ = ⌊1177849 / 2298⌋ = 512; ⌊(2·74·133 + 149) / 298⌋ = ⌊19833 / 298⌋ = 66
        assertThat(USD.gross(574)).isEqualTo(512L);
        assertThat(USD.fee(74)).isEqualTo(66L);
        // W1: nothing refunded, 1025 − 133
        assertThat(USD.stake(0, 0)).isEqualTo(892L);
        // W2: (1025 − 512) − (133 − 66) = 513 − 67
        assertThat(USD.stake(574, 74)).isEqualTo(446L);
    }

    @Test
    void exact_half_rounds_up() {
        // 1 · 3 / 2 = 1.5
        assertThat(new SettlementRate(2, 0, 3, 0).gross(1)).isEqualTo(2L);
    }

    @Test
    void no_fee_converts_to_zero() {
        assertThat(new SettlementRate(1_000, 0, 892, 0).fee(5)).isZero();
    }

    @Test
    void stake_never_negative() {
        assertThat(USD.stake(1_149, 0)).isZero();
        assertThat(USD.stake(1_149, 149)).isZero();
    }

    @Test
    void share_after_a_refund_is_capped_at_the_stake() {
        // W5: DisputeShare.of(1149,149,574,74,1149) = (575, 75, 500); 513 − 67 = 446, stake 446
        DisputeShare s = DisputeShare.of(1_149, 149, 574, 74, 1_149);
        assertThat(s).isEqualTo(new DisputeShare(575, 75, 500));
        assertThat(USD.gross(575)).isEqualTo(513L);
        assertThat(USD.fee(75)).isEqualTo(67L);
        assertThat(USD.organizerShare(s, 574, 74)).isEqualTo(446L);
        // a stake smaller than the converted share caps it
        assertThat(USD.organizerShare(s, 1_000, 74)).isEqualTo(USD.stake(1_000, 74));
    }

    @Test
    void full_lost_dispute_on_a_usd_order_is_892_eur() {
        // W3: DisputeShare.of(1149,149,0,0,1149) = (1149, 149, 1000); 1025 − 133 = 892
        DisputeShare s = DisputeShare.of(1_149, 149, 0, 0, 1_149);
        assertThat(USD.organizerShare(s, 0, 0)).isEqualTo(892L);
    }

    @Test
    void overflow_throws() {
        assertThatThrownBy(() -> new SettlementRate(3, 0, Long.MAX_VALUE, 0).gross(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void unstamped_order_has_no_rate() {
        Order o = new Order();
        o.setTotalMinor(1_149);
        o.setApplicationFeeMinor(149);
        assertThatThrownBy(() -> SettlementRate.of(o)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_stamped_order_reads_its_four_figures() {
        Order o = new Order();
        o.setTotalMinor(1_149);
        o.setApplicationFeeMinor(149);
        o.setSettlementCurrency("eur");
        o.setSettlementGrossMinor(1_025L);
        o.setSettlementFeeMinor(133L);
        assertThat(SettlementRate.of(o)).isEqualTo(USD);
    }
}
