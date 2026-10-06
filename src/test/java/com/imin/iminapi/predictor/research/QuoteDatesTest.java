package com.imin.iminapi.predictor.research;

import com.imin.iminapi.predictor.research.QuoteDates.QuoteDate;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteDatesTest {

    private static final LocalDate SAT_17_OCT = LocalDate.of(2026, 10, 17);

    @Test
    void extractsFrenchAndEnglishForms() {
        assertThat(QuoteDates.extract("Rendez-vous sam. 17 oct. 2026 au Rex"))
                .containsExactly(new QuoteDate(2026, 10, 17, DayOfWeek.SATURDAY));
        assertThat(QuoteDates.extract("le 17/10/2026 puis le 18.10.26"))
                .containsExactly(new QuoteDate(2026, 10, 17, null), new QuoteDate(2026, 10, 18, null));
        assertThat(QuoteDates.extract("Saturday, October 17th, 2026 - doors 23:00"))
                .containsExactly(new QuoteDate(2026, 10, 17, DayOfWeek.SATURDAY));
        assertThat(QuoteDates.extract("Published 2026-10-17"))
                .containsExactly(new QuoteDate(2026, 10, 17, null));
        assertThat(QuoteDates.extract("du 16 au 18 décembre, et le 1er mai"))
                .containsExactly(new QuoteDate(null, 12, 16, null), new QuoteDate(null, 12, 18, null),
                        new QuoteDate(null, 5, 1, null));
        assertThat(QuoteDates.extract("doors 20.00, 30 février, 19:30")).isEmpty();
        assertThat(QuoteDates.extract(null)).isEmpty();
    }

    @Test
    void dottedNumbersAreDatesOnlyWithAYear() {
        // A dotted pair is a price or a time as often as a date; a slashed pair stays a year-less date.
        assertThat(QuoteDates.extract("le 21.05 au Rex, entrée 12.10 €")).isEmpty();
        assertThat(QuoteDates.extract("le 21.05.2026")).containsExactly(new QuoteDate(2026, 5, 21, null));
        assertThat(QuoteDates.extract("le 21/05")).containsExactly(new QuoteDate(null, 5, 21, null));
        assertThat(QuoteDates.classify("entrée 12.10 €", LocalDate.of(2026, 10, 12), 7).inWindow()).isEmpty();
    }

    @Test
    void yearlessDateResolvesToTheNearestYear() {
        // A window crossing 31 Dec: "30 décembre" before a 2 Jan night, "2 janvier" after a 31 Dec night.
        assertThat(QuoteDates.classify("le 30 décembre", LocalDate.of(2027, 1, 2), 7).inWindow())
                .containsExactly(LocalDate.of(2026, 12, 30));
        assertThat(QuoteDates.classify("samedi 2 janvier", LocalDate.of(2026, 12, 31), 7).inWindow())
                .containsExactly(LocalDate.of(2027, 1, 2));
        QuoteDates.Classified out = QuoteDates.classify("17 octobre", SAT_17_OCT, 7);
        assertThat(out.inWindow()).containsExactly(SAT_17_OCT);
        assertThat(out.stale()).isFalse();
        assertThat(QuoteDates.classify("17 mars", SAT_17_OCT, 7).inWindow()).isEmpty();
    }

    @Test
    void explicitEarlierYearIsStale() {
        QuoteDates.Classified out = QuoteDates.classify("Edition 2025 : vendredi 17 octobre 2025", SAT_17_OCT, 7);
        assertThat(out.inWindow()).isEmpty();
        assertThat(out.stale()).isTrue();
        // A weekday the stated year contradicts does not count as a date in the window.
        assertThat(QuoteDates.classify("vendredi 17 octobre 2026", SAT_17_OCT, 7).inWindow()).isEmpty();
        assertThat(QuoteDates.classify("17 octobre 2026", SAT_17_OCT, 7).stale()).isFalse();
    }

    @Test
    void weekdayThatOnlyFitsAnEarlierYearIsStale() {
        // 13 January is a Tuesday in 2026 and a Wednesday in 2027.
        LocalDate wed13Jan2027 = LocalDate.of(2027, 1, 13);
        QuoteDates.Classified stale = QuoteDates.classify("grève le mardi 13 janvier", wed13Jan2027, 7);
        assertThat(stale.inWindow()).isEmpty();
        assertThat(stale.stale()).isTrue();
        QuoteDates.Classified fits = QuoteDates.classify("grève le mercredi 13 janvier", wed13Jan2027, 7);
        assertThat(fits.inWindow()).containsExactly(wed13Jan2027);
        assertThat(fits.stale()).isFalse();
    }
}
