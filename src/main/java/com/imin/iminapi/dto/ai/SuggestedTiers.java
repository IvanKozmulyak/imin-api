package com.imin.iminapi.dto.ai;

import com.imin.iminapi.dto.PricingRecommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The one place the three suggested ticket tiers are derived from a pricing recommendation and a
 * capacity. {@code ConceptStudioService} and {@code ConceptSetService} both answer with tiers on
 * different endpoints and each carried a line-for-line copy of this — same 12/24/250 defaults,
 * same Early Bird / Standard / Door split, same 1/5–3/5–1/5 allocation — so a pricing tweak had to
 * be made twice or the two endpoints silently disagreed.
 */
public final class SuggestedTiers {

    private static final BigDecimal DEFAULT_MIN = new BigDecimal("12");
    private static final BigDecimal DEFAULT_MAX = new BigDecimal("24");
    private static final int DEFAULT_CAPACITY = 250;

    private SuggestedTiers() {}

    public static List<SuggestedTierDto> build(PricingRecommendation prices, Integer capacity) {
        BigDecimal min = prices.suggestedMinPrice() == null ? DEFAULT_MIN : prices.suggestedMinPrice();
        BigDecimal max = prices.suggestedMaxPrice() == null ? DEFAULT_MAX : prices.suggestedMaxPrice();
        BigDecimal mid = min.add(max).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        int cap = capacity == null ? DEFAULT_CAPACITY : capacity;
        return List.of(
                new SuggestedTierDto("Early Bird", min.movePointRight(2).intValueExact(), Math.max(1, cap / 5)),
                new SuggestedTierDto("Standard",   mid.movePointRight(2).intValueExact(), Math.max(1, cap * 3 / 5)),
                new SuggestedTierDto("Door",       max.movePointRight(2).intValueExact(), Math.max(1, cap / 5)));
    }
}
