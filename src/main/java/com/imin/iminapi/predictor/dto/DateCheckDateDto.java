package com.imin.iminapi.predictor.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One candidate date. {@code rank} is null outside the high/mid coverage buckets; checked/applicable count
 * distinct star questions, the same rule as {@code coverage}.
 */
public record DateCheckDateDto(LocalDate date, String verdict, int riskScore, int oppScore, BigDecimal coverage,
                               String coverageBucket, Integer rank, int checkedCount, int applicableCount,
                               List<BreakdownLine> breakdown, List<FindingDto> findings, List<NotChecked> notChecked,
                               List<ActionDto> actions) {

    /** A found row's points, {@code min(maxPointsPerFinding, strength × weight)}. */
    public record BreakdownLine(String questionId, String kind, String sourceKind, int points) {}

    public record NotChecked(String questionId, String sourceKind, String reason) {}
}
