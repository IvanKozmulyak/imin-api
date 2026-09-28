package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audienceplan.service.PortraitLlmClient.Citation;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.StoredGroup;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.WebSource;
import com.imin.iminapi.util.EventNormalization;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the extraction answer into groups the portrait may show. Code rules, not the model, decide: sources must be
 * this run's search results, sizes are never taken from the model, identity labels and numbers in text are dropped.
 */
final class PortraitResearchParser {

    static final int MAX_GROUPS = 4;
    static final int MAX_LABEL = 120;
    static final int MAX_DESCRIPTION = 300;
    static final int MAX_SOURCES = 5;
    static final Set<String> BASES = Set.of("genre_first", "regulars", "students", "none");
    static final String CITED = "cited";
    static final String ASSUMED = "assumed";
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IdentityLabelGuard identity;

    PortraitResearchParser(IdentityLabelGuard identity) {
        this.identity = identity;
    }

    /**
     * @param text       the extraction answer (a JSON object, possibly wrapped in prose)
     * @param citations  the research call's url_citation annotations
     * @param towns      town name or key → city key of the towns a group may name
     * @return the groups to store; empty when the answer is unusable or nothing survives the rules
     */
    List<StoredGroup> parse(String text, List<Citation> citations, Map<String, String> towns) {
        JsonNode root = json(text);
        if (root == null || !root.path("groups").isArray()) return List.of();
        Map<String, Citation> cited = new LinkedHashMap<>();
        for (Citation c : citations) {
            String key = normalize(c.url());
            if (key != null) cited.putIfAbsent(key, c);
        }
        Map<String, String> townKeys = new LinkedHashMap<>();
        towns.forEach((name, key) -> townKeys.put(EventNormalization.cityKey(name), key));

        List<StoredGroup> out = new ArrayList<>();
        for (JsonNode g : root.path("groups")) {
            if (out.size() == MAX_GROUPS) break;
            String label = clean(g.path("label"));
            if (label == null || label.length() > MAX_LABEL || DIGIT.matcher(label).find()) continue;
            String description = clean(g.path("description"));
            // Numbers come from open data only; a description that states one is not shown.
            if (description != null && (description.length() > MAX_DESCRIPTION || DIGIT.matcher(description).find())) {
                description = null;
            }
            if (identity.labelsIdentity(label) || identity.labelsIdentity(description)) continue;

            String basis = clean(g.path("basis"));
            basis = basis != null && BASES.contains(basis.toLowerCase(Locale.ROOT)) ? basis.toLowerCase(Locale.ROOT)
                    : "none";

            Set<String> groupTowns = new LinkedHashSet<>();
            for (JsonNode t : g.path("towns")) {
                String key = townKeys.get(EventNormalization.cityKey(t.asText("")));
                if (key != null) groupTowns.add(key);
            }

            List<WebSource> sources = new ArrayList<>();
            boolean allCited = true;
            int urls = 0;
            for (JsonNode u : g.path("sourceUrls")) {
                urls++;
                Citation c = cited.get(normalize(u.asText("")));
                if (c == null) {
                    allCited = false;
                } else if (sources.size() < MAX_SOURCES && sources.stream().noneMatch(s -> s.url().equals(c.url()))) {
                    sources.add(new WebSource(c.url(), title(c)));
                }
            }
            String confidence = urls > 0 && allCited ? CITED : ASSUMED;
            out.add(new StoredGroup(label, description, basis, List.copyOf(groupTowns), List.copyOf(sources),
                    confidence));
        }
        return List.copyOf(out);
    }

    /** The page title as shown, or its host when the title labels people by identity or is blank. */
    private String title(Citation c) {
        String t = c.title() == null ? null : c.title().strip();
        if (t != null && !t.isEmpty() && !identity.labelsIdentity(t)) return t;
        try {
            return new URI(c.url().trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** Scheme and host lower-cased, fragment and trailing slash dropped; null for anything but http(s). */
    static String normalize(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme) || u.getHost() == null) return null;
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
            String port = u.getPort() < 0 ? "" : ":" + u.getPort();
            return u.getHost().toLowerCase(Locale.ROOT) + port + path + query;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode json(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return JSON.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(JsonNode n) {
        if (n == null || !n.isTextual()) return null;
        String s = n.asText().strip().replaceAll("\\s+", " ");
        return s.isEmpty() ? null : s;
    }
}
