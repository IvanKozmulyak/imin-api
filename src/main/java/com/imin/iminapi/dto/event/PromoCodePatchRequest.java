package com.imin.iminapi.dto.event;

public record PromoCodePatchRequest(
        String code,
        Integer discountPct,
        Integer maxUses,
        Boolean enabled
) {}
