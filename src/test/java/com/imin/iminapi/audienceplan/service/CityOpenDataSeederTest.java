package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.model.CityOpenData;
import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.audienceplan.opendata.OpenDataCity;
import com.imin.iminapi.audienceplan.opendata.OpenDataJson;
import com.imin.iminapi.audienceplan.opendata.OpenDataSeed;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import com.imin.iminapi.audienceplan.repository.CityOpenDataRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real migration and the startup seeding on the shared Postgres. */
@IminIntegrationTest
class CityOpenDataSeederTest {

    @Autowired CityOpenDataRepository rows;
    @Autowired CityOpenDataSeeder seeder;

    @Test
    void startupStoredEverySeedRowWithItsProvenance() {
        for (OpenDataSeed.Row seed : OpenDataSeed.load()) {
            OpenDataset d = seed.dataset();
            CityOpenData row = rows.findByCityKeyAndDataset(seed.cityKey(), d.key()).orElseThrow();
            assertThat(row.getHeadline()).as(seed.cityKey() + "/" + d.key()).isEqualTo(seed.headline());
            assertThat(row.getRefPeriod()).as(seed.cityKey() + "/" + d.key()).isEqualTo(seed.refPeriod());
            assertThat(row.getPayload()).as(seed.cityKey() + "/" + d.key()).isEqualTo(OpenDataJson.write(seed.figures()));
            assertThat(row.getLicence()).as(seed.cityKey() + "/" + d.key()).isEqualTo(d.licence());
            assertThat(row.getAttribution()).as(seed.cityKey() + "/" + d.key()).isEqualTo(d.attribution());
            assertThat(row.getSourceUrl()).as(seed.cityKey() + "/" + d.key()).isEqualTo(seed.sourceUrl());
            assertThat(row.getFetchedAt()).as(seed.cityKey() + "/" + d.key()).isEqualTo(seed.fetchedAt());
            assertThat(row.getExpiresAt()).as(seed.cityKey() + "/" + d.key()).isEqualTo(seed.fetchedAt().plus(d.ttl()));
        }
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
}
