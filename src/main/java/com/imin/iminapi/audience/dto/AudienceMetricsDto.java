package com.imin.iminapi.audience.dto;

import java.util.List;

public record AudienceMetricsDto(
        long totalMembers,
        long buyers,
        long prospects,
        long subscribedMailable,
        double subscribedPct,
        List<Integer> listGrowth8w,
        double repeatAttendeePct,
        long explicitConsent,
        long softOptIn,
        double unsubRatePct,
        double complaintRatePct
) {}
