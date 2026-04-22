package com.imin.iminapi.dto;

import java.util.List;

public record StyleReferenceSummary(
        String tag,
        String label,
        List<String> imageUrls
) {}
