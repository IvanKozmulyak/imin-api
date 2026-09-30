package com.imin.iminapi.predictor.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A row of the date-check history list. */
public record DateCheckSummaryDto(UUID id, String status, String city, String genreFamily, Instant createdAt,
                                  List<DateSummary> dates) {

    public record DateSummary(LocalDate date, String verdict, int riskScore, Integer rank) {}
}
