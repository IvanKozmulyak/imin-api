package com.imin.iminapi.audienceplan.opendata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the unquoted, comma-separated seed files; a row with the wrong column count fails the load. */
final class OpenDataCsv {

    private OpenDataCsv() {}

    static List<Map<String, String>> read(String classpathLocation) {
        try (InputStream in = OpenDataCsv.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (in == null) throw new IllegalStateException("Missing open-data file " + classpathLocation);
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R");
            String[] header = lines[0].trim().split(",", -1);
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) continue;
                String[] cells = lines[i].trim().split(",", -1);
                if (cells.length != header.length) {
                    throw new IllegalStateException(classpathLocation + " line " + (i + 1) + ": expected "
                            + header.length + " columns, got " + cells.length);
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.length; c++) {
                    row.put(header[c], cells[c].isBlank() ? null : cells[c].trim());
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable open-data file " + classpathLocation, e);
        }
    }
}
