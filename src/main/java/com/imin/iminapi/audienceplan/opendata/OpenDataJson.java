package com.imin.iminapi.audienceplan.opendata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** JSON in and out of the open-data sources and the {@code payload} column. */
public final class OpenDataJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenDataJson() {}

    public static JsonNode parse(String body) {
        if (body == null || body.isBlank()) throw new OpenDataFetchException("Empty answer from open-data source");
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new OpenDataFetchException("Unreadable answer from open-data source", e);
        }
    }

    public static String write(Map<String, Object> figures) {
        try {
            return JSON.writeValueAsString(figures);
        } catch (Exception e) {
            throw new IllegalStateException("Open-data figures are not serialisable", e);
        }
    }
}
