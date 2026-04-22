package com.imin.iminapi.dto;

import java.util.List;

public record ReferenceImageSet(
        String subStyleTag,
        List<String> referenceUrls,
        List<String> referenceIds
) {}
