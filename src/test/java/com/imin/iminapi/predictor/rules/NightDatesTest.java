package com.imin.iminapi.predictor.rules;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class NightDatesTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Test
    void nightWindowCrossesMidnightLocal() {
        // Paris 01:30 on 18 Oct
        assertThat(NightDates.nightOf(Instant.parse("2026-10-17T23:30:00Z"), PARIS)).isEqualTo(LocalDate.of(2026, 10, 17));
        // Paris 00:30 on 19 Oct: the local date alone would say 19
        assertThat(NightDates.nightOf(Instant.parse("2026-10-18T22:30:00Z"), PARIS)).isEqualTo(LocalDate.of(2026, 10, 18));
        // Paris 03:30 on 18 Oct: the UTC date alone would say 18
        assertThat(NightDates.nightOf(Instant.parse("2026-10-18T01:30:00Z"), PARIS)).isEqualTo(LocalDate.of(2026, 10, 17));
        // Paris 06:00 starts a new night
        assertThat(NightDates.nightOf(Instant.parse("2026-10-18T04:00:00Z"), PARIS)).isEqualTo(LocalDate.of(2026, 10, 18));
    }

    @Test
    void autumnDstNight2026EventMapsToItsNight() {
        assertThat(NightDates.nightOf(Instant.parse("2026-10-25T01:30:00Z"), PARIS)).isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    void springDstNight2027EventMapsToItsNight() {
        assertThat(NightDates.nightOf(Instant.parse("2027-03-28T01:30:00Z"), PARIS)).isEqualTo(LocalDate.of(2027, 3, 27));
    }

    @Test
    void kyivUsesItsOwnZone() {
        Instant i = Instant.parse("2026-10-18T03:30:00Z"); // Kyiv 06:30, Paris 05:30

        assertThat(NightDates.nightOf(i, KYIV)).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(NightDates.nightOf(i, PARIS)).isEqualTo(LocalDate.of(2026, 10, 17));
    }

    @Test
    void nightStartIsLocalSixAm() {
        assertThat(NightDates.nightStart(LocalDate.of(2026, 10, 18), PARIS)).isEqualTo(Instant.parse("2026-10-18T04:00:00Z"));
        assertThat(NightDates.nightStart(LocalDate.of(2026, 10, 25), PARIS)).isEqualTo(Instant.parse("2026-10-25T05:00:00Z"));
    }
}
