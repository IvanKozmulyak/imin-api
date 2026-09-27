package com.imin.iminapi.audienceplan.opendata;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The committed open-data extracts under {@code audienceplan/open-data/}, one CSV per dataset. */
public final class OpenDataSeed {

    private static final Set<String> META = Set.of("city_key", "ref_period", "fetched_on", "source_url");

    /** One seed row; {@code figures} are the non-meta columns, blank cells as null. */
    public record Row(OpenDataset dataset, String cityKey, String refPeriod, Instant fetchedAt,
                      String sourceUrl, Long headline, Map<String, Object> figures) {}

    private OpenDataSeed() {}

    public static List<Row> load() {
        List<Row> rows = new ArrayList<>();
        for (OpenDataset dataset : OpenDataset.values()) {
            rows.addAll(rows(dataset, "audienceplan/open-data/" + dataset.key() + ".csv"));
        }
        return rows;
    }

    static List<Row> rows(OpenDataset dataset, String location) {
        List<Row> rows = new ArrayList<>();
        for (Map<String, String> csv : OpenDataCsv.read(location)) {
            Map<String, Object> figures = new LinkedHashMap<>();
            for (Map.Entry<String, String> cell : csv.entrySet()) {
                if (META.contains(cell.getKey())) continue;
                figures.put(cell.getKey(), cell.getValue() == null ? null : Long.parseLong(cell.getValue()));
            }
            Long headline = dataset.headlineField() == null ? null : (Long) figures.get(dataset.headlineField());
            rows.add(new Row(dataset, require(csv, "city_key"), require(csv, "ref_period"),
                    LocalDate.parse(require(csv, "fetched_on")).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    require(csv, "source_url"), headline, figures));
        }
        return rows;
    }

    private static String require(Map<String, String> csv, String column) {
        String v = csv.get(column);
        if (v == null) throw new IllegalStateException("Open-data seed row without " + column + ": " + csv);
        return v;
    }
}
