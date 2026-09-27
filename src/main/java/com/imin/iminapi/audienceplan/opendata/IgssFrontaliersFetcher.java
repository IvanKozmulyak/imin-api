package com.imin.iminapi.audienceplan.opendata;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IGSS (CC0): people employed in Luxembourg by French commune of residence, latest reference date.
 * One workbook serves every city, so the parsed totals are kept for an hour.
 */
public class IgssFrontaliersFetcher implements OpenDataFetcher {

    static final String URL =
            "https://igss.gouvernement.lu/dam-assets/publications/statistiques/emploi/emploi-total-communeres-f.xlsx";
    static final String SHEET = "Données source";
    static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;
    static final Duration MEMO_TTL = Duration.ofHours(1);

    static final String COL_DATE = "Date de référence";
    static final String COL_DEPARTMENT = "Département";
    static final String COL_COMMUNE = "Commune";
    static final String COL_COUNT = "Nombre de personnes en emploi";

    private final RestClient http;
    private final Clock clock;
    private Totals memo;
    private Instant memoAt;

    public IgssFrontaliersFetcher(RestClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    @Override
    public OpenDataset dataset() { return OpenDataset.FRONTALIERS; }

    @Override
    public FetchedFigure fetch(OpenDataCity city) {
        Totals totals = totals();
        Long count = totals.byCommune().get(key(city.department(), city.name()));
        if (count == null) {
            throw new OpenDataFetchException("IGSS has no row for " + city.cityKey() + " at " + totals.date());
        }
        Map<String, Object> figures = new LinkedHashMap<>();
        figures.put("employed_in_luxembourg", count);
        return new FetchedFigure(totals.date(), count, figures, URL);
    }

    private synchronized Totals totals() {
        Instant now = clock.instant();
        if (memo != null && memoAt.plus(MEMO_TTL).isAfter(now)) return memo;
        Totals parsed = parse(download());
        memo = parsed;
        memoAt = now;
        return parsed;
    }

    private byte[] download() {
        try {
            byte[] bytes = http.get().uri(URI.create(URL)).exchange((req, res) -> {
                if (!res.getStatusCode().is2xxSuccessful()) {
                    throw new OpenDataFetchException("IGSS answered " + res.getStatusCode().value());
                }
                try (InputStream in = res.getBody()) {
                    return in.readNBytes(MAX_DOWNLOAD_BYTES + 1);
                }
            });
            if (bytes == null || bytes.length == 0) throw new OpenDataFetchException("IGSS file is empty");
            if (bytes.length > MAX_DOWNLOAD_BYTES) throw new OpenDataFetchException("IGSS file is larger than expected");
            return bytes;
        } catch (RestClientException e) {
            throw new OpenDataFetchException("IGSS download failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Sums every genre and status per (département, commune) at the latest reference date. */
    static Totals parse(byte[] xlsx) {
        Map<String, Integer> cols = new HashMap<>();
        String[] latest = {null};
        Map<String, Long> byCommune = new HashMap<>();
        XlsxSheetReader.readSheet(xlsx, SHEET, row -> {
            if (cols.isEmpty()) {
                for (int i = 0; i < row.size(); i++) {
                    if (row.get(i) != null) cols.put(row.get(i).trim(), i);
                }
                if (!cols.keySet().containsAll(List.of(COL_DATE, COL_DEPARTMENT, COL_COMMUNE, COL_COUNT))) {
                    throw new OpenDataFetchException("IGSS header changed: " + cols.keySet());
                }
                return;
            }
            String date = cell(row, cols.get(COL_DATE));
            String commune = cell(row, cols.get(COL_COMMUNE));
            if (date == null || commune == null) return;
            if (latest[0] != null && date.compareTo(latest[0]) < 0) return;
            if (latest[0] == null || date.compareTo(latest[0]) > 0) {
                latest[0] = date;
                byCommune.clear();
            }
            long n = count(cell(row, cols.get(COL_COUNT)));
            byCommune.merge(key(cell(row, cols.get(COL_DEPARTMENT)), commune), n, Long::sum);
        });
        if (latest[0] == null) throw new OpenDataFetchException("IGSS sheet has no commune rows");
        return new Totals(latest[0].replace('.', '-'), Map.copyOf(byCommune));
    }

    private static String cell(List<String> row, int col) {
        if (col >= row.size()) return null;
        String v = row.get(col);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static long count(String raw) {
        if (raw == null) throw new OpenDataFetchException("IGSS row without a count");
        try {
            return Math.round(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            throw new OpenDataFetchException("IGSS count is not a number: " + raw, e);
        }
    }

    private static String key(String department, String commune) {
        return (department == null ? "" : department) + "|" + commune;
    }

    record Totals(String date, Map<String, Long> byCommune) {}
}
