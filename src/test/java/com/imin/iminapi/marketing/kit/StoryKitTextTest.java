package com.imin.iminapi.marketing.kit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class StoryKitTextTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    // 2026-10-31T23:30Z is already Sunday 1 November in Paris (UTC+1 after the October clock change).
    private static final Instant SUNDAY_MORNING_PARTY = Instant.parse("2026-10-31T23:30:00Z");

    @Test
    void theDateLineUsesTheEventZone() {
        assertThat(StoryKitText.whenLine(SUNDAY_MORNING_PARTY, PARIS, "en")).isEqualTo("Sun 1 Nov · 00:30");
    }

    // 00:30 Sunday is posted as Saturday night. 05:30Z is 06:30 Sunday in Paris (a day start) but 05:30 in UTC (still night).
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-10-31T21:00:00Z | 2026-10-24 | 2026-10-29 | 2026-10-31",
            "2026-10-31T23:30:00Z | 2026-10-24 | 2026-10-29 | 2026-10-31",
            "2026-11-01T05:30:00Z | 2026-10-25 | 2026-10-30 | 2026-11-01",
    })
    void postDaysFollowTheNightTheEventStartsInInTheEventZone(String start, String d7, String d2, String d0) {
        StoryKitText.Plan plan = StoryKitText.frames(Instant.parse(start), PARIS, Instant.parse("2026-10-01T10:00:00Z"));

        assertThat(plan.skipped()).isEmpty();
        assertThat(plan.made()).containsExactly(
                new StoryKitText.Frame("d7", LocalDate.parse(d7)),
                new StoryKitText.Frame("d2", LocalDate.parse(d2)),
                new StoryKitText.Frame("d0", LocalDate.parse(d0)));
    }

    // Night of Sat 31 Oct (Paris). "now" is read in Paris: at 23:30Z on 29 Oct it is already 30 Oct there.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-10-22T10:00:00Z | d7,d2,d0 | ''",
            "2026-10-24T10:00:00Z | d7,d2,d0 | ''",
            "2026-10-27T10:00:00Z | d2,d0    | d7",
            "2026-10-29T23:30:00Z | d0       | d7,d2",
    })
    void framesWhoseDayHasPassedAreSkipped(String now, String made, String skipped) {
        StoryKitText.Plan plan = StoryKitText.frames(SUNDAY_MORNING_PARTY, PARIS, Instant.parse(now));

        assertThat(plan.made()).extracting(StoryKitText.Frame::key).containsExactlyElementsOf(keys(made));
        assertThat(plan.skipped()).extracting(StoryKitText.Frame::key).containsExactlyElementsOf(keys(skipped));
    }

    private static java.util.List<String> keys(String csv) {
        String t = csv.replace("'", "").trim();
        return t.isEmpty() ? java.util.List.of() : java.util.List.of(t.split("\\s*,\\s*"));
    }

    // 16:30Z is 17:30 in Paris but 16:30 in UTC; 17:30Z is 12:30 in New York; 00:30 local is still the night before.
    @ParameterizedTest
    @CsvSource({
            "2026-11-01T16:30:00Z, Europe/Paris,      Tonight",
            "2026-11-01T17:30:00Z, America/New_York,  Today",
            "2026-10-31T23:30:00Z, Europe/Paris,      Tonight",
            "2026-11-01T05:30:00Z, Europe/Paris,      Today",
    })
    void dayOfHeadlineFollowsTheLocalStartHour(String start, String zone, String expected) {
        assertThat(StoryKitText.headline("d0", Instant.parse(start), ZoneId.of(zone), "en")).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"FR,fr", "fr,fr", "ES,es", "UA,uk", "DE,en", "GB,en"})
    void defaultLocaleComesFromTheOrganizationCountry(String country, String expected) {
        assertThat(StoryKitText.defaultLocale(country)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Mars/Olympus"})
    void aMissingOrUnknownZoneReadsAsUtc(String timezone) {
        assertThat(StoryKitText.zone(timezone)).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void defaultLocaleIsEnglishWithoutACountry() {
        assertThat(StoryKitText.defaultLocale(null)).isEqualTo("en");
    }
}
