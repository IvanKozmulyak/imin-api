package com.imin.iminapi.audienceplan.opendata;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MESR Atlas régional: students enrolled in one commune, latest academic year, {@code regroupement = TOTAL}.
 * The source sums the rows per year server-side, so no page of raw rows can truncate the total.
 */
public class MesrAtlasFetcher implements OpenDataFetcher {

    static final String BASE_URL = "https://data.enseignementsup-recherche.gouv.fr/api/explore/v2.1/catalog/datasets/"
            + "fr-esr-atlas_regional-effectifs-d-etudiants-inscrits/records";

    private final RestClient http;

    public MesrAtlasFetcher(RestClient http) {
        this.http = http;
    }

    @Override
    public OpenDataset dataset() { return OpenDataset.STUDENTS; }

    static String url(OpenDataCity city) {
        String where = "geo_id=\"" + city.inseeCode() + "\" and niveau_geographique=\"Commune\" and regroupement=\"TOTAL\"";
        return BASE_URL + "?select=" + enc("annee_universitaire,sum(effectif) as effectif,count(*) as n_rows,"
                        + "count(effectif) as n_effectif")
                + "&where=" + enc(where)
                + "&group_by=" + enc("annee_universitaire")
                + "&order_by=" + enc("annee_universitaire desc")
                + "&limit=1";
    }

    @Override
    public FetchedFigure fetch(OpenDataCity city) {
        String url = url(city);
        String body;
        try {
            body = http.get().uri(URI.create(url)).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new OpenDataFetchException("MESR call failed: " + e.getClass().getSimpleName(), e);
        }
        JsonNode latest = OpenDataJson.parse(body).path("results").path(0);
        String year = latest.path("annee_universitaire").asText("");
        if (year.isEmpty()) {
            throw new OpenDataFetchException("MESR returned no enrolment for " + city.cityKey());
        }
        JsonNode effectif = latest.path("effectif");
        long rowsInYear = latest.path("n_rows").asLong(-1);
        // sum() skips nulls, so a year with any row lacking a count would be silently understated
        if (!effectif.isNumber() || rowsInYear <= 0 || rowsInYear != latest.path("n_effectif").asLong(-2)) {
            throw new OpenDataFetchException("MESR row without effectif for " + city.cityKey());
        }
        long students = effectif.asLong();
        Map<String, Object> figures = new LinkedHashMap<>();
        figures.put("students", students);
        return new FetchedFigure(year, students, figures, url);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
