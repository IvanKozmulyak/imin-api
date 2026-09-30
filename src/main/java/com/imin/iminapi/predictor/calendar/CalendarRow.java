package com.imin.iminapi.predictor.calendar;

import java.time.LocalDate;
import java.util.Objects;

/** One reference calendar fact before it is stored. {@code region} "" = the whole country. */
public record CalendarRow(String country, String region, LocalDate date, LocalDate endDate,
                          String kind, String name, String sourceUrl) {

    public CalendarRow {
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        region = region == null ? "" : region;
        if (endDate != null && !endDate.isAfter(date)) endDate = null;
    }

    /** The table's unique key (country, region, calendar_date, kind, name). */
    public String key() {
        return country + "|" + region + "|" + date + "|" + kind + "|" + name;
    }
}
