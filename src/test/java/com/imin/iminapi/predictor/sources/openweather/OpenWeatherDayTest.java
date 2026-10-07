package com.imin.iminapi.predictor.sources.openweather;

import com.imin.iminapi.predictor.service.WeatherService.Weather;
import com.imin.iminapi.predictor.sources.openweather.OpenWeatherDay.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenWeatherDayTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalDate JUNE_5 = LocalDate.parse("2026-06-05");
    // Paris is UTC+2 in June: the local day 2026-06-05 runs 2026-06-04T22:00Z .. 2026-06-05T22:00Z.
    private static final Instant JUNE_5_START = Instant.parse("2026-06-04T22:00:00Z");

    private static final double[] POPS = {0.1, 0.2, 0.3, 0.45, 0.5, 0.62, 0.4, 0.15};
    private static final double[] TEMPS = {14.2, 13.8, 15.0, 18.4, 21.6, 20.1, 17.3, 15.5};

    private static List<Step> eight(Instant start, Double[] pops, Double[] temps) {
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) out.add(new Step(start.plusSeconds(i * 3 * 3600L), temps[i], pops[i]));
        return out;
    }

    private static Double[] boxed(double[] v) {
        Double[] out = new Double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i];
        return out;
    }

    @Test
    void fullDayGivesMaxPopAndTemp() {
        List<Step> steps = eight(JUNE_5_START, boxed(POPS), boxed(TEMPS));
        assertThat(steps.get(7).dt()).isEqualTo(Instant.parse("2026-06-05T19:00:00Z"));

        assertThat(OpenWeatherDay.of(steps, PARIS, JUNE_5)).isEqualTo(new Weather(62, 21.6));
    }

    @Test
    void boundaryStepAtDayEndExcludedAtDayStartIncluded() {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(JUNE_5_START, 15.0, 0.9));                              // dayStart: in
        for (int i = 1; i < 8; i++) steps.add(new Step(JUNE_5_START.plusSeconds(i * 3 * 3600L), 15.0, 0.1));
        steps.add(new Step(Instant.parse("2026-06-05T22:00:00Z"), 30.0, 1.0));      // dayEnd: out

        Weather w = OpenWeatherDay.of(steps, PARIS, JUNE_5);

        assertThat(w.precipProbabilityMaxPct()).isEqualTo(90);
        assertThat(w.tempMaxC()).isEqualTo(15.0);
    }

    @Test
    void partialDayIsNull() {
        List<Step> steps = eight(JUNE_5_START, boxed(POPS), boxed(TEMPS)).subList(1, 8);

        assertThat(steps).hasSize(7);
        assertThat(OpenWeatherDay.of(steps, PARIS, JUNE_5)).isNull();
    }

    /** A missing pop or temp nulls only its own field; both missing everywhere leaves nothing to show. */
    @ParameterizedTest(name = "pop missing at {0}, temp missing at {1}")
    @CsvSource(nullValues = "null", value = {"3, -1, null, 21.6", "-1, 0, 62, null", "all, all, null, null"})
    void partialNulls(String popMissing, String tempMissing, Integer pop, Double temp) {
        Double[] pops = missing(boxed(POPS), popMissing);
        Double[] temps = missing(boxed(TEMPS), tempMissing);

        Weather w = OpenWeatherDay.of(eight(JUNE_5_START, pops, temps), PARIS, JUNE_5);

        if (pop == null && temp == null) assertThat(w).isNull();
        else assertThat(w).isEqualTo(new Weather(pop, temp));
    }

    private static Double[] missing(Double[] values, String at) {
        if (at.equals("all")) return new Double[values.length];
        int i = Integer.parseInt(at);
        if (i >= 0) values[i] = null;
        return values;
    }

    @Test
    void shortDstDayNeedsSeven() {
        LocalDate dstDay = LocalDate.parse("2026-03-29");
        // 23 h day: 2026-03-28T23:00Z (00:00 CET) .. 2026-03-29T22:00Z (00:00 CEST)
        Instant start = Instant.parse("2026-03-28T23:00:00Z");
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 7; i++) steps.add(new Step(start.plusSeconds(i * 3 * 3600L), 10.0 + i, 0.2));

        assertThat(OpenWeatherDay.of(steps, PARIS, dstDay)).isEqualTo(new Weather(20, 16.0));
        assertThat(OpenWeatherDay.of(steps.subList(0, 6), PARIS, dstDay)).isNull();
    }

    @Test
    void roundsAndClampsPop() {
        Double[] pops = boxed(POPS);
        pops[2] = 0.625;
        assertThat(OpenWeatherDay.of(eight(JUNE_5_START, pops, boxed(TEMPS)), PARIS, JUNE_5).precipProbabilityMaxPct())
                .isEqualTo(63);

        pops[2] = 1.0;
        assertThat(OpenWeatherDay.of(eight(JUNE_5_START, pops, boxed(TEMPS)), PARIS, JUNE_5).precipProbabilityMaxPct())
                .isEqualTo(100);

        pops[2] = 1.4;
        assertThat(OpenWeatherDay.of(eight(JUNE_5_START, pops, boxed(TEMPS)), PARIS, JUNE_5).precipProbabilityMaxPct())
                .isEqualTo(100);
    }

    @Test
    void repeatedStepDoesNotCountTowardsCoverage() {
        List<Step> steps = new ArrayList<>(eight(JUNE_5_START, boxed(POPS), boxed(TEMPS)).subList(0, 7));
        steps.add(steps.get(0));

        assertThat(OpenWeatherDay.of(steps, PARIS, JUNE_5)).isNull();
    }
}
