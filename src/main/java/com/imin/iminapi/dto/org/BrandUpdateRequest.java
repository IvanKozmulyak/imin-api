package com.imin.iminapi.dto.org;

import java.util.List;

/**
 * PUT /api/v1/org/brand — full replace of the three scalar fields. All fields are required;
 * the FE always sends its full controlled state. accentColors == [] clears the palette.
 * No bean-validation annotations: the per-index hex/count rules are validated manually in
 * OrgBrandService so we can emit indexed field keys (accentColors[1]) the FE can highlight.
 */
public record BrandUpdateRequest(
        String brandName,
        List<String> accentColors,
        Boolean logoOnPosters
) {}
