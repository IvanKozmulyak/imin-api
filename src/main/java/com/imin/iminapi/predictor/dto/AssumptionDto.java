package com.imin.iminapi.predictor.dto;

/**
 * An input the check assumed. {@code field} uses the request's names (audienceAge, communities, priceMinor,
 * startHour, buyingLeadDays); {@code source} is organizer | profile. priceMinor is always [min, max].
 */
public record AssumptionDto(String field, Object value, String source, boolean estimate, String sourcedUrl) {}
