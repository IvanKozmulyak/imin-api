package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.UniversalRules;
import com.imin.iminapi.dto.Vibe;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the Vibe Library ({@code vibes.yaml}) — 12 genre-mapped poster presets, a genre→vibe
 * auto-suggest map, and the universal rules. Deterministic config; makes no network calls.
 */
@Component
public class VibeLibrary {

    private static final Logger log = LoggerFactory.getLogger(VibeLibrary.class);

    private final ResourceLoader resourceLoader;
    private final String configFile;

    private Map<String, Vibe> byId = Collections.emptyMap();
    private Map<String, String> genreToVibeId = Collections.emptyMap();
    private UniversalRules universalRules =
            new UniversalRules(List.of("4:5", "1:1", "9:16", "16:9"), "", "");
    private String fallbackVibeId;

    public VibeLibrary(
            ResourceLoader resourceLoader,
            @Value("${poster.vibes.config-file:classpath:vibes.yaml}") String configFile) {
        this.resourceLoader = resourceLoader;
        this.configFile = configFile;
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        Resource resource = resourceLoader.getResource(configFile);
        if (!resource.exists()) {
            log.warn("Vibe config not found at {} — VibeLibrary will be empty", configFile);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null) return;

            fallbackVibeId = str(root, "fallback_vibe");

            Object ur = root.get("universal_rules");
            if (ur instanceof Map<?, ?> urm) {
                universalRules = new UniversalRules(
                        toStringList(((Map<String, Object>) urm).get("aspect_ratios")),
                        str((Map<String, Object>) urm, "negative_prompt"),
                        str((Map<String, Object>) urm, "ip_rule"));
            }

            Map<String, Vibe> ids = new LinkedHashMap<>();
            Map<String, String> genres = new LinkedHashMap<>();
            Object vibesRaw = root.get("vibes");
            if (vibesRaw instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    Vibe v = toVibe((Map<String, Object>) m);
                    ids.put(v.id(), v);
                    for (String g : v.genres()) {
                        genres.putIfAbsent(g.toLowerCase(), v.id());
                    }
                }
            }
            byId = ids;
            genreToVibeId = genres;
            log.info("VibeLibrary loaded: {} vibes, {} genre mappings, fallback={}",
                    byId.size(), genreToVibeId.size(), fallbackVibeId);
        } catch (IOException e) {
            log.error("Failed to load vibe config {}", configFile, e);
        }
    }

    private Vibe toVibe(Map<String, Object> m) {
        return new Vibe(
                str(m, "id"),
                str(m, "name"),
                toStringList(m.get("genres")),
                str(m, "visual_style"),
                toStringList(m.get("palette")),
                str(m, "typography"),
                str(m, "composition"),
                toStringList(m.get("mood_tags")),
                toStringList(m.get("avoid")),
                str(m, "model_route"),
                toStringList(m.get("references")),
                str(m, "style_id"),
                str(m, "layout_template"),
                Boolean.TRUE.equals(m.get("text_only")));
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /** All vibes in declaration order. */
    public List<Vibe> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Vibe> byId(String id) {
        return Optional.ofNullable(id).map(byId::get);
    }

    public boolean hasVibe(String id) {
        return id != null && byId.containsKey(id);
    }

    public int size() {
        return byId.size();
    }

    public UniversalRules universalRules() {
        return universalRules;
    }

    /**
     * Suggest a vibe for a free-text music genre. Exact (case-insensitive) match against the
     * genre map; falls back to the configured fallback vibe (then the first vibe) when no match.
     */
    public Vibe suggestForGenre(String genre) {
        if (genre != null) {
            String id = genreToVibeId.get(genre.trim().toLowerCase());
            if (id != null && byId.containsKey(id)) {
                return byId.get(id);
            }
        }
        if (fallbackVibeId != null && byId.containsKey(fallbackVibeId)) {
            return byId.get(fallbackVibeId);
        }
        return byId.isEmpty() ? null : byId.values().iterator().next();
    }
}
