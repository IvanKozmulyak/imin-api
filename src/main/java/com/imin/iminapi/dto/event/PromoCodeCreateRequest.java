package com.imin.iminapi.dto.event;

public record PromoCodeCreateRequest(
        String code,
        Integer discountPct,
        Integer maxUses,
        Boolean enabled        // optional — null defaults to true
) {}
