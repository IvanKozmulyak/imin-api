package com.imin.iminapi.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class CountryTimeZonesTest {

    @ParameterizedTest
    @CsvSource({
            "FR, Europe/Paris", "NL, Europe/Amsterdam", "DE, Europe/Berlin", "ES, Europe/Madrid",
            "GB, Europe/London", "UA, Europe/Kyiv",
            "US, America/New_York", "CA, America/Toronto", "IT, Europe/Rome", "PT, Europe/Lisbon",
            "fr, Europe/Paris", "'  de  ', Europe/Berlin",
    })
    void looksUpTheCapitalZoneIgnoringCaseAndSpaces(String country, String zone) {
        assertThat(CountryTimeZones.zoneFor(country)).contains(zone);
        // Guards typos and tz-database drift (e.g. Europe/Kyiv vs the old Europe/Kiev).
        assertThat(ZoneId.getAvailableZoneIds()).contains(zone);
    }

    @Test
    void unknownNullAndBlankCountriesAreEmpty() {
        assertThat(CountryTimeZones.zoneFor(null)).isEmpty();
        assertThat(CountryTimeZones.zoneFor("")).isEmpty();
        assertThat(CountryTimeZones.zoneFor("  ")).isEmpty();
        assertThat(CountryTimeZones.zoneFor("ZZ")).isEmpty();
        assertThat(CountryTimeZones.zoneFor("XY")).isEmpty();
    }
}
