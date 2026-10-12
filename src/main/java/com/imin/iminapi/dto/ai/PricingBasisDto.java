package com.imin.iminapi.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/** Where the suggested ticket prices came from, so the card never presents defaults as market data. */
@Schema(description = "Where the suggested ticket prices came from.")
public record PricingBasisDto(
        @Schema(description = "comparables: medians of listed tier prices of other public imin events; "
                + "default: typical starting prices, not based on imin events.",
                allowableValues = {"comparables", "default"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String source,
        @Schema(description = "Number of comparable events behind the medians (3..500). Null for default.",
                nullable = true)
        Integer comparableEvents,
        @Schema(description = "The requested genre, trimmed, as matched. Null when none was given.", nullable = true)
        String genre,
        @Schema(description = "ISO 3166-1 alpha-2 country of the organization the comparables were matched in. "
                + "Null for default.", nullable = true)
        String country) {

    public static final String COMPARABLES = "comparables";
    public static final String DEFAULT = "default";
}
