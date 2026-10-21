package com.imin.iminapi.marketing.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventStartFactTest {

    /** 23:30Z on Friday 30 October is already Saturday 00:30 in Paris. */
    @Test
    void localDayAndTimeInTheEventZone() {
        Instant start = Instant.parse("2026-10-30T23:30:00Z");

        assertThat(EventStartFact.describe(start, "Europe/Paris"))
                .isEqualTo("Saturday 31 October 2026, 00:30 (Europe/Paris)");
        assertThat(EventStartFact.describe(start, "UTC")).isEqualTo("Friday 30 October 2026, 23:30 (UTC)");
        assertThat(EventStartFact.describe(start, "Not/AZone")).isEqualTo("Friday 30 October 2026, 23:30 (UTC)");
        assertThat(EventStartFact.describe(start, null)).isEqualTo("Friday 30 October 2026, 23:30 (UTC)");
        assertThat(EventStartFact.describe(null, "Europe/Paris")).isNull();
    }
}
