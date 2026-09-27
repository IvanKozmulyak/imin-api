package com.imin.iminapi.audienceplan.opendata;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** INSEE census population by single-year age for one commune (Melodi, no key); headline = ages 18-35. */
public class InseeMelodiFetcher implements OpenDataFetcher {

    static final String BASE_URL = "https://api.insee.fr/melodi/data/DS_RP_TD_POPULATION_AGESEX_PRINC";
    static final int AGE_FROM = 18;
    static final int AGE_TO = 35;
    static final int MAX_ATTEMPTS = 3;
    static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(60);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(2);

    private final RestClient http;
    private final MelodiThrottle throttle;
    private final Sleeper sleeper;

    public InseeMelodiFetcher(RestClient http, MelodiThrottle throttle, Sleeper sleeper) {
        this.http = http;
        this.throttle = throttle;
        this.sleeper = sleeper;
    }

    @Override
    public OpenDataset dataset() { return OpenDataset.INSEE_AGE; }

    static String url(OpenDataCity city) {
        return BASE_URL + "?GEO=COM-" + city.inseeCode() + "&SEX=_T&maxResult=10000";
    }

    @Override
    public FetchedFigure fetch(OpenDataCity city) {
        String url = url(city);
        JsonNode body = OpenDataJson.parse(get(url));
        if (body.path("paging").hasNonNull("next")) {
            throw new OpenDataFetchException("Melodi answer is paged; refusing a partial sum for " + city.cityKey());
        }
        // Latest census year only; values are weighted estimates, summed then rounded once.
        String latest = null;
        Map<String, Map<String, Double>> byYear = new HashMap<>();
        for (JsonNode obs : body.path("observations")) {
            JsonNode dims = obs.path("dimensions");
            if (!"POP".equals(dims.path("RP_MEASURE").asText()) || !"_T".equals(dims.path("SEX").asText())) continue;
            JsonNode value = obs.path("measures").path("OBS_VALUE_NIVEAU").path("value");
            if (!value.isNumber()) continue;
            String year = dims.path("TIME_PERIOD").asText();
            byYear.computeIfAbsent(year, y -> new HashMap<>()).put(dims.path("AGE").asText(), value.asDouble());
            if (latest == null || year.compareTo(latest) > 0) latest = year;
        }
        if (latest == null) {
            throw new OpenDataFetchException("Melodi returned no population for " + city.cityKey());
        }
        Map<String, Double> ages = byYear.get(latest);
        double youth = 0;
        for (int age = AGE_FROM; age <= AGE_TO; age++) {
            Double v = ages.get("Y" + age);
            if (v == null) {
                throw new OpenDataFetchException("Melodi is missing age " + age + " for " + city.cityKey());
            }
            youth += v;
        }
        Double total = ages.get("_T");
        Map<String, Object> figures = new LinkedHashMap<>();
        figures.put("pop_18_35", Math.round(youth));
        figures.put("pop_total", total == null ? null : Math.round(total));
        return new FetchedFigure(latest, Math.round(youth), figures, url);
    }

    private String get(String url) {
        for (int attempt = 1; ; attempt++) {
            try {
                throttle.acquire();
                return http.get().uri(URI.create(url)).retrieve().body(String.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new OpenDataFetchException("Melodi still rate-limited after " + attempt + " attempts", e);
                }
                sleepQuietly(backoff(e.getResponseHeaders() == null ? null
                        : e.getResponseHeaders().getFirst("Retry-After")));
            } catch (RestClientException e) {
                throw new OpenDataFetchException("Melodi call failed: " + e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenDataFetchException("Interrupted while waiting for Melodi", e);
            }
        }
    }

    static Duration backoff(String retryAfter) {
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds >= 0) return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
            } catch (NumberFormatException ignored) {
                // HTTP-date form: fall through to the default
            }
        }
        return DEFAULT_BACKOFF;
    }

    private void sleepQuietly(Duration d) {
        try {
            sleeper.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenDataFetchException("Interrupted while backing off from Melodi", e);
        }
    }
}
