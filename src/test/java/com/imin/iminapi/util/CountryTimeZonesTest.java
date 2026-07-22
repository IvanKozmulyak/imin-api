package com.imin.iminapi.util;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CountryTimeZonesTest {

    @Test
    void maps_primary_european_countries_to_capital_zone() {
        assertThat(CountryTimeZones.zoneFor("FR")).contains("Europe/Paris");
        assertThat(CountryTimeZones.zoneFor("NL")).contains("Europe/Amsterdam");
        assertThat(CountryTimeZones.zoneFor("DE")).contains("Europe/Berlin");
        assertThat(CountryTimeZones.zoneFor("ES")).contains("Europe/Madrid");
        assertThat(CountryTimeZones.zoneFor("GB")).contains("Europe/London");
        assertThat(CountryTimeZones.zoneFor("UA")).contains("Europe/Kyiv");
    }

    @Test
    void lookup_is_case_insensitive_and_trims() {
        assertThat(CountryTimeZones.zoneFor("fr")).contains("Europe/Paris");
        assertThat(CountryTimeZones.zoneFor("  de  ")).contains("Europe/Berlin");
    }

    @Test
    void unknown_null_and_blank_countries_are_empty() {
        assertThat(CountryTimeZones.zoneFor(null)).isEmpty();
        assertThat(CountryTimeZones.zoneFor("")).isEmpty();
        assertThat(CountryTimeZones.zoneFor("  ")).isEmpty();
        assertThat(CountryTimeZones.zoneFor("ZZ")).isEmpty();  // not a real country
        assertThat(CountryTimeZones.zoneFor("XY")).isEmpty();
    }

    @Test
    void every_mapped_zone_is_a_resolvable_iana_zone_in_this_runtime() {
        // Guards against typos and tz-database drift (e.g. Europe/Kyiv vs the old Europe/Kiev).
        for (String iso : Map.of(
                "FR", "", "DE", "", "NL", "", "ES", "", "GB", "", "UA", "",
                "US", "", "CA", "", "IT", "", "PT", "").keySet()) {
            String zone = CountryTimeZones.zoneFor(iso).orElseThrow();
            assertThat(ZoneId.getAvailableZoneIds())
                    .as("zone %s for %s must resolve in this JVM's tz database", zone, iso)
                    .contains(zone);
        }
    }
}
