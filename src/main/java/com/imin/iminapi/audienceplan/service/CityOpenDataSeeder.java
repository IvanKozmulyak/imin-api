package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.model.CityOpenData;
import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.audienceplan.opendata.OpenDataJson;
import com.imin.iminapi.audienceplan.opendata.OpenDataSeed;
import com.imin.iminapi.audienceplan.repository.CityOpenDataRepository;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Inserts the committed open-data extracts for rows that do not exist yet; never overwrites a stored row. */
@Component
public class CityOpenDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(CityOpenDataSeeder.class);

    private final CityOpenDataRepository rows;
    private final OpenDataCities cities;

    public CityOpenDataSeeder(CityOpenDataRepository rows, OpenDataCities cities) {
        this.rows = rows;
        this.cities = cities;
    }

    /** A broken seed must not stop the API booting; the refresh job fills the gap. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int inserted = seed();
            log.info("CityOpenDataSeeder: {} seed rows inserted", inserted);
        } catch (RuntimeException e) {
            log.error("CityOpenDataSeeder: seeding failed: {} {}", e.getClass().getSimpleName(),
                    LogSafe.redact(e.getMessage()));
        }
    }

    public int seed() {
        int inserted = 0;
        for (OpenDataSeed.Row seed : OpenDataSeed.load()) {
            if (cities.find(seed.cityKey()).isEmpty()) {
                log.warn("CityOpenDataSeeder: seed row for unknown city {} skipped", seed.cityKey());
                continue;
            }
            if (rows.findByCityKeyAndDataset(seed.cityKey(), seed.dataset().key()).isPresent()) continue;
            CityOpenData row = new CityOpenData();
            row.setCityKey(seed.cityKey());
            row.setDataset(seed.dataset().key());
            row.setRefPeriod(seed.refPeriod());
            row.setHeadline(seed.headline());
            row.setPayload(OpenDataJson.write(seed.figures()));
            row.setSourceUrl(seed.sourceUrl());
            row.setLicence(seed.dataset().licence());
            row.setAttribution(seed.dataset().attribution());
            row.setFetchedAt(seed.fetchedAt());
            row.setExpiresAt(seed.fetchedAt().plus(seed.dataset().ttl()));
            try {
                rows.save(row);
                inserted++;
            } catch (DataAccessException e) {
                log.warn("CityOpenDataSeeder: {} for {} not inserted (another instance may have): {}",
                        seed.dataset().key(), seed.cityKey(), LogSafe.redact(e.getMessage()));
            }
        }
        return inserted;
    }
}
