package com.imin.iminapi.audienceplan.opendata;

import com.imin.iminapi.util.EventNormalization;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The cities the open-data loaders can query, from {@code audienceplan/open-data/cities.csv}. */
public class OpenDataCities {

    static final String LOCATION = "audienceplan/open-data/cities.csv";

    private final Map<String, OpenDataCity> byKey;

    public OpenDataCities(Collection<OpenDataCity> cities) {
        Map<String, OpenDataCity> map = new LinkedHashMap<>();
        for (OpenDataCity c : cities) map.put(c.cityKey(), c);
        this.byKey = Map.copyOf(map);
    }

    public static OpenDataCities load() {
        return load(LOCATION);
    }

    static OpenDataCities load(String location) {
        Map<String, OpenDataCity> map = new LinkedHashMap<>();
        for (Map<String, String> row : OpenDataCsv.read(location)) {
            OpenDataCity city = new OpenDataCity(row.get("city_key"), row.get("name"), row.get("country"),
                    row.get("insee_code"), row.get("department"));
            if (!EventNormalization.cityKey(city.name()).equals(city.cityKey())) {
                throw new IllegalStateException(location + ": key " + city.cityKey() + " does not match " + city.name());
            }
            map.put(city.cityKey(), city);
        }
        return new OpenDataCities(map.values());
    }

    public Optional<OpenDataCity> find(String cityKey) {
        return cityKey == null ? Optional.empty() : Optional.ofNullable(byKey.get(cityKey));
    }

    public Collection<OpenDataCity> all() {
        return byKey.values();
    }
}
