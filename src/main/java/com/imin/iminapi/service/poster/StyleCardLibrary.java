package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.Rgb;
import com.imin.iminapi.dto.StyleCard;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads the per-vibe style cards committed under {@code classpath:vibes/style-cards/{vibeId}.yaml}.
 * Each file is parsed into a {@link StyleCard} keyed by its filename stem (the {@code vibeId}); the
 * card body never carries its own id. Deterministic config; makes no network calls. A single bad
 * file is logged and skipped — loading never throws.
 *
 * <p>Drives the {@code CreativeDirectionSampler} (sampling pools) and the art-director prompt, and
 * supplies the palette for Recraft curated rendering + the style-adherence validation gate.
 */
@Component
public class StyleCardLibrary {

    private static final Logger log = LoggerFactory.getLogger(StyleCardLibrary.class);

    private final ResourceLoader resourceLoader;
    private final String location;

    private Map<String, StyleCard> byId = Collections.emptyMap();

    public StyleCardLibrary(
            ResourceLoader resourceLoader,
            @Value("${poster.style-cards.location:classpath*:vibes/style-cards/*.yaml}") String location) {
        this.resourceLoader = resourceLoader;
        this.location = location;
    }

    @PostConstruct
    void load() {
        Map<String, StyleCard> cards = new LinkedHashMap<>();
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(resourceLoader);
        Resource[] resources;
        try {
            resources = resolver.getResources(location);
        } catch (Exception e) {
            log.warn("Failed to resolve style-card location {} — StyleCardLibrary will be empty",
                    location, e);
            return;
        }
        for (Resource resource : resources) {
            String vibeId = vibeIdOf(resource);
            if (vibeId == null) {
                log.warn("Skipping style-card resource with no resolvable filename: {}", resource);
                continue;
            }
            try {
                StyleCard card = parse(resource, vibeId);
                if (card != null) {
                    cards.put(vibeId, card);
                }
            } catch (Exception e) {
                log.warn("Failed to parse style card {} — skipping", resource.getFilename(), e);
            }
        }
        byId = cards;
        log.info("StyleCardLibrary loaded: {} style cards {}", byId.size(), byId.keySet());
    }

    @SuppressWarnings("unchecked")
    private StyleCard parse(Resource resource, String vibeId) throws Exception {
        try (InputStream in = resource.getInputStream()) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?>)) {
                log.warn("Style card {} is empty or not a mapping — skipping", resource.getFilename());
                return null;
            }
            Map<String, Object> root = (Map<String, Object>) loaded;

            Map<String, Object> heroSubjects = Collections.emptyMap();
            Object hs = root.get("hero_subjects");
            if (hs instanceof Map<?, ?> hsm) {
                heroSubjects = (Map<String, Object>) hsm;
            }

            return new StyleCard(
                    vibeId,
                    str(root, "medium"),
                    toPalette(root.get("palette")),
                    toStringList(heroSubjects.get("people")),
                    toStringList(heroSubjects.get("object")),
                    toStringList(root.get("compositions")),
                    toStringList(root.get("accents")),
                    toStringList(root.get("palette_twists")),
                    toStringList(root.get("type_treatments")),
                    toStringList(root.get("example_prompts")));
        }
    }

    private static String vibeIdOf(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        String stem = dot >= 0 ? filename.substring(0, dot) : filename;
        return stem.isBlank() ? null : stem;
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

    /** Parse a list-of-lists {@code [[r,g,b], ...]} into {@link Rgb} triples; tolerates non-lists. */
    private static List<Rgb> toPalette(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Rgb> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof List<?> inner)) continue;
            List<Number> numbers = new ArrayList<>(inner.size());
            for (Object o : inner) {
                if (o instanceof Number n) numbers.add(n);
            }
            if (!numbers.isEmpty()) out.add(Rgb.of(numbers));
        }
        return out;
    }

    /** The style card for a vibe id, if loaded. */
    public Optional<StyleCard> get(String vibeId) {
        return Optional.ofNullable(vibeId).map(byId::get);
    }

    public boolean has(String vibeId) {
        return vibeId != null && byId.containsKey(vibeId);
    }

    public int size() {
        return byId.size();
    }

    /** All loaded vibe ids in load order. */
    public Set<String> vibeIds() {
        return Collections.unmodifiableSet(byId.keySet());
    }
}
