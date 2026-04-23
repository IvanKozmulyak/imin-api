package com.imin.iminapi.dto;

import java.math.BigDecimal;

public record PricingRecommendation(
        BigDecimal suggestedMinPrice,
        BigDecimal suggestedMaxPrice,
        String pricingNotes
) {}
