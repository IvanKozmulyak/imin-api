package com.imin.iminapi.dto.event;

import com.imin.iminapi.model.PromoCode;

import java.util.UUID;

public record PromoCodeDto(UUID id, UUID eventId, String code,
                           int discountPct, int maxUses, int usedCount, boolean enabled) {
    public static PromoCodeDto from(PromoCode p) {
        return new PromoCodeDto(p.getId(), p.getEventId(), p.getCode(),
                p.getDiscountPct(), p.getMaxUses(), p.getUsedCount(), p.isEnabled());
    }
}
