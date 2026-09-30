package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.util.CountryTimeZones;
import com.imin.iminapi.util.EventNormalization;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * What the organizer told us about a planned night, as the rule engine reads it.
 * {@code knownEvents} and {@code communities}: null = not provided, empty = the organizer said none.
 */
public record DateCheckInput(String city, String country, String postalCode, Double venueLat, Double venueLng,
                             String genreFamily, String subGenre, Integer capacity, Long priceMinor, String format,
                             Integer startHour, Integer endHour, List<String> lineup, List<KnownEvent> knownEvents,
                             UUID orgId, LocalDate today, List<Integer> audienceAge, List<String> communities,
                             Integer buyingLeadDays) {

    /** An event the organizer knows about on or near the date; {@code strength} is 1 (maybe) or 2 (sure). */
    public record KnownEvent(String name, LocalDate date, String venue, int strength) {
        public KnownEvent {
            Objects.requireNonNull(date, "date");
            if (strength < 1 || strength > 2) {
                throw new IllegalArgumentException("known event strength must be 1 or 2, was " + strength);
            }
        }
    }

    public DateCheckInput {
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(today, "today");
        country = country.trim().toUpperCase(Locale.ROOT);
        lineup = lineup == null ? List.of() : List.copyOf(lineup);
        knownEvents = knownEvents == null ? null : List.copyOf(knownEvents);
        audienceAge = audienceAge == null ? null : List.copyOf(audienceAge);
        communities = communities == null ? null : List.copyOf(communities);
    }

    /** The venue's zone from its country; UTC when the country has no mapped zone. */
    public ZoneId zone() {
        return CountryTimeZones.zoneFor(country).<ZoneId>map(ZoneId::of).orElse(ZoneOffset.UTC);
    }

    public String cityKey() {
        return EventNormalization.cityKey(city);
    }
}
