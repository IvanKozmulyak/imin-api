package com.imin.iminapi.predictor.sources.prim;

import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One GET of the IDFM "arrets" export (Licence Ouverte 2.0, no key): stop positions, plus one point per stop zone at
 * the mean of its stops. Only the three columns it reads are selected, so no free-text column can shift a row.
 */
public class IdfmStopsClient {

    private static final Logger log = LoggerFactory.getLogger(IdfmStopsClient.class);
    /** Already encoded, so it is passed as a URI and never re-encoded. */
    static final String URL = "https://data.iledefrance-mobilites.fr/api/explore/v2.1/catalog/datasets/arrets/exports/csv"
            + "?delimiter=%3B&select=arrid%2Czdaid%2Carrgeopoint";
    /** A truncated export would wipe the reference; 37,957 rows on 2026-10-07. */
    static final int MIN_ROWS = 30_000;
    // A loose Île-de-France box: a swapped or garbled point falls outside it.
    static final double MIN_LAT = 47.9, MAX_LAT = 49.5, MIN_LNG = 1.2, MAX_LNG = 3.8;
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** {@code ref} is the feed's stop id: {@code IDFM:<arrid>} or {@code IDFM:monomodalStopPlace:<zdaid>}. */
    public record Stop(String ref, double lat, double lng) {}

    /** {@code stops} is non-empty only when {@code status} is OK. */
    public record Outcome(Status status, List<Stop> stops) {
        public Outcome {
            stops = List.copyOf(stops);
        }

        static Outcome of(Status status) {
            return new Outcome(status, List.of());
        }
    }

    private final RestClient http;

    public IdfmStopsClient(RestClient.Builder builder) {
        this.http = builder.build();
    }

    /** Never throws: non-2xx and transport errors are FAILED, a body that does not parse to enough stops UNUSABLE. */
    public Outcome fetch() {
        try {
            return http.get().uri(URI.create(URL)).exchange((req, res) -> {
                if (!res.getStatusCode().is2xxSuccessful()) {
                    log.warn("IdfmStopsClient: HTTP {}", res.getStatusCode().value());
                    return Outcome.of(Status.FAILED);
                }
                String body;
                try {
                    body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("IdfmStopsClient: reading the body failed ({})", e.getClass().getSimpleName());
                    return Outcome.of(Status.FAILED);
                }
                List<Stop> stops = parse(body);
                return stops.isEmpty() ? Outcome.of(Status.UNUSABLE) : new Outcome(Status.OK, stops);
            }, true);
        } catch (RestClientException e) {
            log.warn("IdfmStopsClient: request failed ({})", e.getClass().getSimpleName());
            return Outcome.of(Status.FAILED);
        }
    }

    /** Stops then zone points; empty when a column is missing or fewer than {@link #MIN_ROWS} rows are valid. */
    static List<Stop> parse(String body) {
        if (body == null) return List.of();
        String[] lines = body.replace("﻿", "").split("\r?\n");
        if (lines.length == 0) return List.of();
        List<String> header = List.of(lines[0].strip().split(";", -1));
        int arrid = header.indexOf("arrid");
        int zdaid = header.indexOf("zdaid");
        int point = header.indexOf("arrgeopoint");
        if (arrid < 0 || zdaid < 0 || point < 0) return List.of();
        int width = Math.max(arrid, Math.max(zdaid, point)) + 1;

        Map<String, Stop> stops = new LinkedHashMap<>();
        Map<String, double[]> zones = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(";", -1);
            if (cells.length < width) continue;
            String stopId = cells[arrid].strip();
            String zoneId = cells[zdaid].strip();
            if (!DIGITS.matcher(stopId).matches() || !DIGITS.matcher(zoneId).matches()) continue;
            double[] ll = point(cells[point]);
            if (ll == null) continue;
            if (stops.putIfAbsent("IDFM:" + stopId, new Stop("IDFM:" + stopId, ll[0], ll[1])) != null) continue;
            double[] sum = zones.computeIfAbsent(zoneId, k -> new double[3]);
            sum[0] += ll[0];
            sum[1] += ll[1];
            sum[2]++;
        }
        if (stops.size() < MIN_ROWS) return List.of();
        List<Stop> out = new ArrayList<>(stops.values());
        zones.forEach((id, s) -> out.add(new Stop("IDFM:monomodalStopPlace:" + id, s[0] / s[2], s[1] / s[2])));
        return out;
    }

    /** {@code "lat, lng"} inside the IDF box, else null. */
    private static double[] point(String raw) {
        String[] parts = raw.split(",");
        if (parts.length != 2) return null;
        try {
            double lat = Double.parseDouble(parts[0].strip());
            double lng = Double.parseDouble(parts[1].strip());
            // written as "inside" so NaN, which fails every comparison, is rejected too
            if (!(lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG)) return null;
            return new double[]{lat, lng};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
