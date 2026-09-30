package com.imin.iminapi.predictor.dto;

import java.util.List;

/** PATCH …/{id}/assumptions: a null field keeps the current value; an explicit {@code []} is an organizer answer. */
public record AssumptionsPatch(List<Integer> audienceAge, List<String> communities, Long priceMinor, Integer startHour,
                               Integer buyingLeadDays) {}
