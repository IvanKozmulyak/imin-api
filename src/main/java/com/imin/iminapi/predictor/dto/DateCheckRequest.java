package com.imin.iminapi.predictor.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * POST /api/v1/predictions/date-checks. Validated by {@code DateCheckValidator} (422 FIELD_INVALID), not bean
 * validation. {@code country} falls back to the org's; {@code knownEvents}/{@code communities}: null = not
 * provided, empty = none. {@code research} is ignored while research is off.
 */
public record DateCheckRequest(String city, String country, String postalCode, UUID eventId, String genreFamily,
                               String subGenre, List<LocalDate> dates, Integer capacity, Long priceMinor,
                               String format, Integer startHour, Integer endHour, List<String> lineup,
                               List<KnownEventInput> knownEvents, List<Integer> audienceAge, List<String> communities,
                               Integer buyingLeadDays, Boolean research) {

    /** An event the organizer knows about; {@code strength} 1 = maybe, 2 = sure. */
    public record KnownEventInput(String name, LocalDate date, String venue, Integer strength) {}
}
