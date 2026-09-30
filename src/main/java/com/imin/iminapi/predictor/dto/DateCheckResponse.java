package com.imin.iminapi.predictor.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stored date check. Keys, enums, numbers and facts only — never rendered prose. {@code researchStatus} is
 * "off" until web research ships.
 */
public record DateCheckResponse(UUID id, String status, String city, String country, String postalCode, UUID eventId,
                                String genreFamily, String subGenre, Integer capacity, Long priceMinor,
                                boolean research, String researchStatus, String questionBankVersion,
                                Instant createdAt, Instant updatedAt, List<AssumptionDto> assumptions,
                                List<DateCheckDateDto> dates) {}
