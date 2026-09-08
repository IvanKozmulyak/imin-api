package com.imin.iminapi.dto.ai;

import com.imin.iminapi.dto.PricingRecommendation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * poster-15: ConceptStudioService and ConceptSetService each carried a line-for-line copy of this
 * split, so the /concept and /concepts endpoints could disagree after a pricing tweak. Both now
 * call this, and this is what they agree on.
 */
class SuggestedTiersTest {

    @Test
    void splitsCapacityOneFifth_threeFifths_oneFifth_atMinMidMax() {
        var tiers = SuggestedTiers.build(
                new PricingRecommendation(new BigDecimal("12.00"), new BigDecimal("24.00"), "ok"), 250);

        assertThat(tiers).extracting(SuggestedTierDto::name)
                .containsExactly("Early Bird", "Standard", "Door");
        assertThat(tiers).extracting(SuggestedTierDto::priceMinor).containsExactly(1200, 1800, 2400);
        assertThat(tiers).extracting(SuggestedTierDto::quantity).containsExactly(50, 150, 50);
    }

    @Test
    void fallsBackTo12_24_250WhenTheRecommendationIsEmpty() {
        var tiers = SuggestedTiers.build(new PricingRecommendation(null, null, null), null);

        assertThat(tiers).extracting(SuggestedTierDto::priceMinor).containsExactly(1200, 1800, 2400);
        assertThat(tiers).extracting(SuggestedTierDto::quantity).containsExactly(50, 150, 50);
    }

    @Test
    void neverEmitsAZeroQuantityTier() {
        var tiers = SuggestedTiers.build(
                new PricingRecommendation(new BigDecimal("10.00"), new BigDecimal("20.00"), "ok"), 1);

        assertThat(tiers).allSatisfy(t -> assertThat(t.quantity()).isGreaterThanOrEqualTo(1));
    }
}
