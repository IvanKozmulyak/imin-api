package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.rules.NightDates;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/** Picks the row of a date check that describes an event: its night if scored, else the best-ranked date. */
public final class DateCheckStaleness {

    /** {@code stale}: the event's night is not among the check's dates, so {@code row} is the fallback. */
    public record Match(DateCheckDate row, boolean stale) {}

    private DateCheckStaleness() {}

    /** Null when the check has no dates. A missing start is stale: there is no night to match. */
    public static Match match(List<DateCheckDate> rowsByDateAsc, Instant startsAt, ZoneId zone) {
        if (rowsByDateAsc == null || rowsByDateAsc.isEmpty()) return null;
        if (startsAt != null) {
            LocalDate night = NightDates.nightOf(startsAt, zone);
            for (DateCheckDate d : rowsByDateAsc) {
                if (night.equals(d.getCandidateDate())) return new Match(d, false);
            }
        }
        DateCheckDate fallback = rowsByDateAsc.stream()
                .filter(d -> d.getRankOrder() != null)
                .min(Comparator.comparingInt(DateCheckDate::getRankOrder))
                .orElseGet(() -> rowsByDateAsc.stream()
                        .min(Comparator.comparing(DateCheckDate::getCandidateDate)).orElseThrow());
        return new Match(fallback, true);
    }
}
