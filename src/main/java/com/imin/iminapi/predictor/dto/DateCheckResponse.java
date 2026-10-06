package com.imin.iminapi.predictor.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stored date check. Keys, enums, numbers and facts only — never rendered prose. {@code researchStatus}: "off"
 * (not asked for or not allowed for the org), "running" ({@code status} is "running" too), "done" (web findings
 * merged, source "web"), or "failed": the calendar result stands, because of a provider error, a timeout, an
 * unusable or unparseable answer, findings without citations, the payload guard, the daily cap, "Check a date"
 * closed for the org when the job ran, or the job running out of attempts.
 */
public record DateCheckResponse(UUID id, String status, String city, String country, String postalCode, UUID eventId,
                                String genreFamily, String subGenre, Integer capacity, Long priceMinor,
                                boolean research, String researchStatus, String questionBankVersion,
                                Instant createdAt, Instant updatedAt, List<AssumptionDto> assumptions,
                                List<DateCheckDateDto> dates) {}
