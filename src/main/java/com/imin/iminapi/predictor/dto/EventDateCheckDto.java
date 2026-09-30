package com.imin.iminapi.predictor.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The event's current date check: the scored row for {@code forDate}; {@code stale} when that is not the event's night. */
public record EventDateCheckDto(UUID id, DateCheckDateDto result, Instant checkedAt, LocalDate forDate,
                                boolean stale) {}
