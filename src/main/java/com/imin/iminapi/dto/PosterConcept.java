package com.imin.iminapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PosterConcept(
        @JsonProperty("sub_style_tag")            String subStyleTag,
        @JsonProperty("color_palette_description") String colorPaletteDescription,
        List<PosterVariant>                        variants
) {}
