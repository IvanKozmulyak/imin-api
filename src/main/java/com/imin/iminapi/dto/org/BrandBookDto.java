package com.imin.iminapi.dto.org;

import com.imin.iminapi.model.Organization;

import java.util.List;

/** GET /api/v1/org/brand and PUT response. logoUrl is null when no logo; accentColors is [] when none. */
public record BrandBookDto(
        String brandName,
        String logoUrl,
        List<String> accentColors,
        boolean logoOnPosters
) {
    public static BrandBookDto from(Organization o) {
        return new BrandBookDto(
                o.getBrandName(),
                o.getBrandLogoUrl(),
                o.getBrandAccentColors() == null ? List.of() : List.copyOf(o.getBrandAccentColors()),
                o.isBrandLogoOnPosters());
    }
}
