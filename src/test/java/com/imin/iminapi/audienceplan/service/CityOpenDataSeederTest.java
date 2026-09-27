package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.model.CityOpenData;
import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.audienceplan.opendata.OpenDataCity;
import com.imin.iminapi.audienceplan.opendata.OpenDataSeed;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import com.imin.iminapi.audienceplan.repository.CityOpenDataRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real migration and the startup seeding on H2. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CityOpenDataSeederTest {

    @Autowired CityOpenDataRepository rows;
    @Autowired CityOpenDataSeeder seeder;

    @Test
    void startupStoredEverySeedRowWithItsProvenance() {
        assertThat(rows.findAll()).hasSize(OpenDataSeed.load().size());

        CityOpenData metzAge = rows.findByCityKeyAndDataset("metz", "insee_age").orElseThrow();
        assertThat(metzAge.getHeadline()).isEqualTo(38_065L);
        assertThat(metzAge.getRefPeriod()).isEqualTo("2023");
        assertThat(metzAge.getPayload()).isEqualTo("{\"pop_18_35\":38065,\"pop_total\":122572}");
        assertThat(metzAge.getLicence()).isEqualTo("Licence Ouverte 2.0");
        assertThat(metzAge.getAttribution()).isEqualTo(OpenDataset.INSEE_AGE.attribution());
        assertThat(metzAge.getSourceUrl()).contains("GEO=COM-57463");
        assertThat(metzAge.getFetchedAt()).isEqualTo(Instant.parse("2026-09-27T00:00:00Z"));
        assertThat(metzAge.getExpiresAt()).isEqualTo(Instant.parse("2026-09-27T00:00:00Z").plus(Duration.ofDays(365)));

        assertThat(rows.findByCityKeyAndDataset("metz", "students").orElseThrow().getHeadline()).isEqualTo(20_588L);
        CityOpenData front = rows.findByCityKeyAndDataset("metz", "frontaliers").orElseThrow();
        assertThat(front.getHeadline()).isEqualTo(6_330L);
        assertThat(front.getLicence()).isEqualTo("CC0 1.0");
        assertThat(front.getExpiresAt()).isEqualTo(Instant.parse("2026-09-27T00:00:00Z").plus(Duration.ofDays(182)));
        CityOpenData osm = rows.findByCityKeyAndDataset("metz", "osm_venues").orElseThrow();
        assertThat(osm.getHeadline()).isNull();
        assertThat(osm.getLicence()).isEqualTo("ODbL 1.0");
        assertThat(osm.getAttribution()).isEqualTo("© OpenStreetMap contributors");
    }

    @Test
    void seedingAgainInsertsNothingAndKeepsStoredRows() {
        CityOpenData row = rows.findByCityKeyAndDataset("nancy", "students").orElseThrow();
        Instant fetchedBefore = row.getFetchedAt();

        assertThat(seeder.seed()).isZero();

        assertThat(rows.findByCityKeyAndDataset("nancy", "students").orElseThrow().getFetchedAt())
                .isEqualTo(fetchedBefore);
    }

    @Test
    void aSeedRowForACityOutsideTheRegistryIsSkipped() {
        CityOpenDataSeeder onlyNancy = new CityOpenDataSeeder(new InMemoryCityOpenDataRepository(),
                new OpenDataCities(List.of(new OpenDataCity("nancy", "Nancy", "FR", "54395", "Meurthe-et-Moselle"))));

        assertThat(onlyNancy.seed()).isEqualTo(OpenDataset.values().length);
    }

    @Test
    void aRowAnotherInstanceInsertedFirstIsNotCounted() {
        InMemoryCityOpenDataRepository repo = new InMemoryCityOpenDataRepository();
        repo.failNextSave = true;
        CityOpenDataSeeder s = new CityOpenDataSeeder(repo, OpenDataCities.load());

        assertThat(s.seed()).isEqualTo(OpenDataSeed.load().size() - 1);
    }

    @Test
    void aFailingSeedDoesNotEscapeTheStartupListener() {
        CityOpenDataSeeder broken = new CityOpenDataSeeder(null, OpenDataCities.load());

        broken.onReady(); // NullPointerException inside is logged, not thrown
    }

    @Test
    void theSeededMetzCensusIsServedFromTheCacheWhileFresh() {
        // Pinned clock and no fetchers: this test can never reach INSEE, whatever the date.
        PublicDataService service = new PublicDataService(rows, OpenDataCities.load(), List.of(),
                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
        OpenDataValue v = service.get("metz", OpenDataset.INSEE_AGE).orElseThrow();

        assertThat(v.headline()).isEqualTo(38_065L);
        assertThat(v.figures()).containsEntry("pop_total", 122_572L);
        assertThat(v.stale()).isFalse();
    }
}
