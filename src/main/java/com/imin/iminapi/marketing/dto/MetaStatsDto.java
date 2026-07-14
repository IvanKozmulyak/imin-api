package com.imin.iminapi.marketing.dto;

public record MetaStatsDto(
        long sent24h,
        long failed24h,
        long dead,
        String lastError
) {}
