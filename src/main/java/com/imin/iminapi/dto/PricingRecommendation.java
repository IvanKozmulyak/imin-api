package com.imin.iminapi.dto;

import com.imin.iminapi.dto.ai.PricingBasisDto;

import java.math.BigDecimal;

/** Suggested min/max ticket price in major units, and what they are based on. */
public record PricingRecommendation(
        BigDecimal suggestedMinPrice,
        BigDecimal suggestedMaxPrice,
        PricingBasisDto basis
) {}
