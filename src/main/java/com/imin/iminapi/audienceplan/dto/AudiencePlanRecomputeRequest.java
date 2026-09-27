package com.imin.iminapi.audienceplan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/** Overrides for a recomputed plan; an omitted field keeps the current plan's value. */
public record AudiencePlanRecomputeRequest(
        @Min(1) @Max(100) Integer targetPct,
        List<String> excludeSegments,
        @Valid AssumptionsOverride assumptions) {

    public record AssumptionsOverride(@DecimalMin("1.0") @DecimalMax("10.0") Double ticketsPerOrder) {}
}
