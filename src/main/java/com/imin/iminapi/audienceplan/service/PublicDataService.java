package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.imin.iminapi.audienceplan.model.CityOpenData;
import com.imin.iminapi.audienceplan.opendata.FetchedFigure;
import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.audienceplan.opendata.OpenDataCity;
import com.imin.iminapi.audienceplan.opendata.OpenDataFetchException;
import com.imin.iminapi.audienceplan.opendata.OpenDataFetcher;
import com.imin.iminapi.audienceplan.opendata.OpenDataJson;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import com.imin.iminapi.audienceplan.repository.CityOpenDataRepository;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public open data per city (INSEE, MESR, IGSS, OSM counts), cached in {@code city_open_data}.
 * Requests read the stored row only ({@link #get}); the refresh job refetches missing or expired rows
 * ({@link #refresh}). A failed source leaves the last row served flagged stale, or nothing: never an invented figure.
 */
@Service
public class PublicDataService {

    private static final Logger log = LoggerFactory.getLogger(PublicDataService.class);

    private final CityOpenDataRepository rows;
    private final OpenDataCities cities;
    private final Map<OpenDataset, OpenDataFetcher> fetchers;
    private final Clock clock;
    /** Per (city, dataset): no new fetch before this instant, after a failure. */
    private final Map<String, Instant> retryNotBefore = new ConcurrentHashMap<>();

    static final Duration FAILURE_BACKOFF = Duration.ofHours(1);

    public PublicDataService(CityOpenDataRepository rows, OpenDataCities cities,
                             List<OpenDataFetcher> fetchers, Clock clock) {
        this.rows = rows;
        this.cities = cities;
        this.fetchers = new EnumMap<>(OpenDataset.class);
        for (OpenDataFetcher f : fetchers) this.fetchers.put(f.dataset(), f);
        this.clock = clock;
    }

    /** Request-time read that never calls a source: the stored row, stale once expired, or empty. */
    public Optional<OpenDataValue> get(String cityKey, OpenDataset dataset) {
        Optional<OpenDataCity> city = cities.find(cityKey);
        if (city.isEmpty() || !dataset.covers(city.get().country())) return Optional.empty();
        Instant now = clock.instant();
        return rows.findByCityKeyAndDataset(cityKey, dataset.key())
                .map(row -> toValue(row, !row.getExpiresAt().isAfter(now)));
    }

    /**
     * Refetches a missing or expired row from its source and stores it. After a failure the same
     * (city, dataset) is not fetched again for {@link #FAILURE_BACKOFF}; the stale row or empty is served meanwhile.
     */
    public Optional<OpenDataValue> refresh(String cityKey, OpenDataset dataset) {
        Optional<OpenDataCity> city = cities.find(cityKey);
        // A French-only source is never asked about a town abroad.
        if (city.isEmpty() || !dataset.covers(city.get().country())) return Optional.empty();
        Optional<CityOpenData> cached = rows.findByCityKeyAndDataset(cityKey, dataset.key());
        Instant now = clock.instant();
        if (cached.isPresent() && cached.get().getExpiresAt().isAfter(now)) {
            return Optional.of(toValue(cached.get(), false));
        }
        OpenDataFetcher fetcher = fetchers.get(dataset);
        if (fetcher == null) {
            return cached.map(row -> toValue(row, true));
        }
        String memoKey = cityKey + '\u0000' + dataset.key();
        Instant notBefore = retryNotBefore.get(memoKey);
        if (notBefore != null && now.isBefore(notBefore)) {
            return cached.map(row -> toValue(row, true));
        }
        FetchedFigure fetched;
        try {
            fetched = fetcher.fetch(city.get());
        } catch (OpenDataFetchException e) {
            retryNotBefore.put(memoKey, now.plus(FAILURE_BACKOFF));
            log.warn("PublicDataService: {} for {} not refreshed, serving {}: {}", dataset.key(), cityKey,
                    cached.isPresent() ? "the stale row" : "nothing", LogSafe.redact(e.getMessage()));
            return cached.map(row -> toValue(row, true));
        }
        CityOpenData row = cached.orElseGet(CityOpenData::new);
        row.setCityKey(cityKey);
        row.setDataset(dataset.key());
        row.setRefPeriod(fetched.refPeriod());
        row.setHeadline(fetched.headline());
        row.setPayload(OpenDataJson.write(fetched.figures()));
        row.setSourceUrl(fetched.sourceUrl());
        row.setLicence(dataset.licence());
        row.setAttribution(dataset.attribution());
        row.setFetchedAt(now);
        row.setExpiresAt(now.plus(dataset.ttl()));
        try {
            row = rows.save(row);
        } catch (DataAccessException e) {
            log.warn("PublicDataService: {} for {} fetched but not stored: {}", dataset.key(), cityKey,
                    LogSafe.redact(e.getMessage()));
        }
        return Optional.of(toValue(row, false));
    }

    public Collection<OpenDataCity> knownCities() {
        return cities.all();
    }

    static OpenDataValue toValue(CityOpenData row, boolean stale) {
        OpenDataset dataset = OpenDataset.fromKey(row.getDataset())
                .orElseThrow(() -> new IllegalStateException("Unknown open dataset " + row.getDataset()));
        return new OpenDataValue(row.getCityKey(), dataset, row.getRefPeriod(), row.getHeadline(),
                figures(row.getPayload()), row.getSourceUrl(), row.getLicence(), row.getAttribution(),
                row.getFetchedAt(), stale);
    }

    private static Map<String, Long> figures(String payload) {
        Map<String, Long> out = new LinkedHashMap<>();
        JsonNode node = OpenDataJson.parse(payload);
        node.properties().forEach(e -> out.put(e.getKey(), e.getValue().isNumber() ? e.getValue().asLong() : null));
        return out;
    }
}
