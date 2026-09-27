package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.opendata.OpenDataCity;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import com.imin.iminapi.util.LogSafe;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Weekly pass over every known city and dataset. Rows refresh when they expire: yearly for the
 * census and students, semi-annual for IGSS. Datasets without a fetcher (OSM) are left as they are.
 */
@Component
public class CityOpenDataRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(CityOpenDataRefreshJob.class);

    private final PublicDataService publicData;

    public CityOpenDataRefreshJob(PublicDataService publicData) {
        this.publicData = publicData;
    }

    @Scheduled(cron = "0 0 4 * * MON", zone = "Europe/Paris")
    @SchedulerLock(name = "city_open_data_refresh", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        int served = 0;
        int missing = 0;
        for (OpenDataCity city : publicData.knownCities()) {
            for (OpenDataset dataset : OpenDataset.values()) {
                if (!dataset.covers(city.country())) continue;
                try {
                    if (publicData.refresh(city.cityKey(), dataset).isPresent()) served++;
                    else missing++;
                } catch (RuntimeException e) {
                    missing++;
                    log.error("CityOpenDataRefreshJob: {} for {} failed: {} {}", dataset.key(), city.cityKey(),
                            e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
                }
            }
        }
        log.info("CityOpenDataRefreshJob: done, {} rows available, {} missing", served, missing);
    }
}
