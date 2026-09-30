package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.service.DateCheckStaleness;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** EU summer time ends 25 Oct 2026, so Paris is UTC+2 on 23–24 Oct; 24 Oct 2026 is a Saturday. */
class DateCheckStalenessTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private static DateCheckDate row(String date, Integer rank) {
        DateCheckDate d = new DateCheckDate();
        d.setCandidateDate(LocalDate.parse(date));
        d.setRankOrder(rank == null ? null : rank.shortValue());
        return d;
    }

    @Test
    void eventNightAmongDatesIsCurrent() {
        List<DateCheckDate> rows = List.of(row("2026-10-23", 2), row("2026-10-30", 1));

        DateCheckStaleness.Match m = DateCheckStaleness.match(rows, Instant.parse("2026-10-30T20:00:00Z"), PARIS);

        assertThat(m.row().getCandidateDate()).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(m.stale()).isFalse();
    }

    @Test
    void missingNightFallsBackToRankOne() {
        List<DateCheckDate> rows = List.of(row("2026-10-23", 2), row("2026-10-30", 1));

        DateCheckStaleness.Match m = DateCheckStaleness.match(rows, Instant.parse("2026-11-06T20:00:00Z"), PARIS);

        assertThat(m.row().getCandidateDate()).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(m.stale()).isTrue();
    }

    @Test
    void unrankedRowsFallBackToEarliest() {
        List<DateCheckDate> rows = List.of(row("2026-10-23", null), row("2026-10-30", null));

        DateCheckStaleness.Match m = DateCheckStaleness.match(rows, Instant.parse("2026-11-06T20:00:00Z"), PARIS);

        assertThat(m.row().getCandidateDate()).isEqualTo(LocalDate.parse("2026-10-23"));
        assertThat(m.stale()).isTrue();
    }

    @Test
    void noStartIsStale() {
        List<DateCheckDate> rows = List.of(row("2026-10-23", 2), row("2026-10-30", 1));

        DateCheckStaleness.Match m = DateCheckStaleness.match(rows, null, PARIS);

        assertThat(m.row().getCandidateDate()).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(m.stale()).isTrue();
    }

    @Test
    void startBeforeSixLocalBelongsToPreviousNight() {
        // 23:30Z on 23 Oct is 01:30 Saturday 24 Oct in Paris, still the night of Friday 23 Oct.
        List<DateCheckDate> rows = List.of(row("2026-10-23", 1));

        DateCheckStaleness.Match m = DateCheckStaleness.match(rows, Instant.parse("2026-10-23T23:30:00Z"), PARIS);

        assertThat(m.row().getCandidateDate()).isEqualTo(LocalDate.parse("2026-10-23"));
        assertThat(m.stale()).isFalse();
    }

    @Test
    void zoneIsTheChecksZone() {
        // 04:30Z is 06:30 in Paris (night of 24 Oct) but 04:30 UTC would be the night of 23 Oct.
        List<DateCheckDate> rows = List.of(row("2026-10-24", 1));
        Instant start = Instant.parse("2026-10-24T04:30:00Z");

        DateCheckStaleness.Match m = DateCheckStaleness.match(rows, start, PARIS);

        assertThat(m.row().getCandidateDate()).isEqualTo(LocalDate.parse("2026-10-24"));
        assertThat(m.stale()).isFalse();
        assertThat(DateCheckStaleness.match(rows, start, ZoneId.of("UTC")).stale()).isTrue();
    }
}
