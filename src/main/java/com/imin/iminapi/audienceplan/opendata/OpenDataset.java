package com.imin.iminapi.audienceplan.opendata;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** The open datasets cached in {@code city_open_data}, with licence, attribution and refresh cadence. */
public enum OpenDataset {

    /** INSEE census, population by single-year age; headline = people aged 18-35. */
    INSEE_AGE("insee_age", "pop_18_35", "Licence Ouverte 2.0", "Source : Insee, recensement de la population", Duration.ofDays(365),
            Set.of("FR")),
    /** MESR Atlas régional, enrolled students (regroupement TOTAL); headline = students. */
    STUDENTS("students", "students", "Licence Ouverte 2.0",
            "Source : MESR, Atlas régional des effectifs d'étudiants", Duration.ofDays(365), Set.of("FR")),
    /** IGSS, people employed in Luxembourg by French commune of residence; headline = that count. */
    FRONTALIERS("frontaliers", "employed_in_luxembourg", "CC0 1.0", "Source : IGSS Luxembourg", Duration.ofDays(182),
            Set.of("FR")),
    /** OpenStreetMap venue counts, seed only; no headline. Counts are kept apart from any venue list of ours (ODbL). */
    OSM_VENUES("osm_venues", null, "ODbL 1.0", "© OpenStreetMap contributors", Duration.ofDays(365), Set.of("FR")),
    /** Wikidata town-centre coordinate (P625) in microdegrees, seed only; towns do not move, so a stale row stays valid. */
    CENTROID("centroid", null, "CC0 1.0", "Source : Wikidata", Duration.ofDays(3650), Set.of("FR", "LU", "DE"));

    private final String key;
    private final String headlineField;
    private final String licence;
    private final String attribution;
    private final Duration ttl;
    private final Set<String> countries;

    OpenDataset(String key, String headlineField, String licence, String attribution, Duration ttl,
                Set<String> countries) {
        this.key = key;
        this.headlineField = headlineField;
        this.licence = licence;
        this.attribution = attribution;
        this.ttl = ttl;
        this.countries = countries;
    }

    public String key() { return key; }
    /** Payload field that is the headline figure; null when the dataset has none. */
    public String headlineField() { return headlineField; }
    public String licence() { return licence; }
    public String attribution() { return attribution; }
    public Duration ttl() { return ttl; }

    /** True when this dataset has figures for towns in {@code country} (ISO alpha-2). */
    public boolean covers(String country) { return country != null && countries.contains(country); }

    public static Optional<OpenDataset> fromKey(String key) {
        for (OpenDataset d : values()) {
            if (d.key.equals(key)) return Optional.of(d);
        }
        return Optional.empty();
    }
}
