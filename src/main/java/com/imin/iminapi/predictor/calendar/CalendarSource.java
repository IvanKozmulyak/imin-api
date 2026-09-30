package com.imin.iminapi.predictor.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** A reference calendar source. A failed fetch returns no batch for that scope, so stored rows stay. */
public interface CalendarSource {

    String key();

    List<Batch> fetch(LocalDate today);

    /** Prefix every source URL of this source starts with; the boot sync runs while none is stored. Null opts out. */
    default String scopePrefix() { return null; }

    /**
     * Rows that replace everything stored under one scope: {@code sourceUrl}, {@code kinds} and
     * {@code calendar_date} within [from, to].
     */
    record Batch(String sourceUrl, Set<String> kinds, LocalDate from, LocalDate to, List<CalendarRow> rows) {

        public Batch {
            kinds = Set.copyOf(kinds);
            rows = List.copyOf(rows);
            if (from.isAfter(to)) throw new IllegalArgumentException("batch window " + from + " > " + to);
            for (CalendarRow r : rows) {
                if (!r.sourceUrl().equals(sourceUrl) || !kinds.contains(r.kind())
                        || r.date().isBefore(from) || r.date().isAfter(to)) {
                    throw new IllegalArgumentException("row outside batch scope: " + r);
                }
            }
        }
    }
}
