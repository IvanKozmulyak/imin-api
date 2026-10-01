package com.imin.iminapi.predictor.sources.openevents;

import com.imin.iminapi.predictor.rules.QuestionBank;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whole-phrase, accent-insensitive keyword matching of a listing's title and upstream keywords against the
 * genre buckets of {@code predictor/genre-keywords-v1.yaml}; the description is never read. Loaded strictly:
 * a bad file stops boot.
 */
public final class GenreMatcher {

    public static final String LOCATION = "classpath:predictor/genre-keywords-v1.yaml";
    private static final Set<String> ROOT_KEYS = Set.of("version", "buckets", "exclude_phrases", "local_events");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final String FILE = "predictor genre-keywords";

    /** {@code genres} in bank order; {@code community} = a local city-wide event keyword matched. */
    public record Match(Set<String> genres, boolean community) {
        public Match {
            genres = Collections.unmodifiableSet(new LinkedHashSet<>(genres));
        }

        /** False = neither a genre nor a local event: the listing is not stored. */
        public boolean matched() {
            return community || !genres.isEmpty();
        }
    }

    private final int version;
    private final Map<String, List<String>> buckets;
    private final List<String> excludePhrases;
    private final List<String> localEvents;

    private GenreMatcher(int version, Map<String, List<String>> buckets, List<String> excludePhrases,
                         List<String> localEvents) {
        this.version = version;
        this.buckets = Collections.unmodifiableMap(buckets);
        this.excludePhrases = List.copyOf(excludePhrases);
        this.localEvents = List.copyOf(localEvents);
    }

    public static GenreMatcher load(ResourceLoader resources) {
        Resource resource = resources.getResource(LOCATION);
        if (!resource.exists()) throw new IllegalStateException(FILE + ": file not found: " + LOCATION);
        try (InputStream in = resource.getInputStream()) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException(FILE + ": cannot read " + LOCATION, e);
        }
    }

    public static GenreMatcher parse(InputStream in) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object raw;
        try {
            raw = new Yaml(new SafeConstructor(options)).load(in);
        } catch (YAMLException e) {
            throw new IllegalStateException(FILE + ": invalid YAML: " + e.getMessage(), e);
        }
        if (!(raw instanceof Map<?, ?> root)) throw new IllegalStateException(FILE + ": root must be a mapping");
        for (Object k : root.keySet()) {
            if (!ROOT_KEYS.contains(String.valueOf(k))) {
                throw new IllegalStateException(FILE + " root: unknown key '" + k + "'");
            }
        }
        if (!(root.get("version") instanceof Integer version) || version < 1) {
            throw new IllegalStateException(FILE + " version: must be an integer of at least 1");
        }
        if (!(root.get("buckets") instanceof Map<?, ?> rawBuckets)) {
            throw new IllegalStateException(FILE + " buckets: expected a mapping");
        }
        Map<String, List<String>> buckets = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawBuckets.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!QuestionBank.GENRE_BUCKETS.contains(name)) {
                throw new IllegalStateException(FILE + " buckets: unknown bucket '" + name + "'");
            }
            buckets.put(name, keywords(e.getValue(), "buckets." + name));
        }
        for (String b : QuestionBank.GENRE_BUCKETS) {
            if (!buckets.containsKey(b)) throw new IllegalStateException(FILE + " buckets: missing bucket '" + b + "'");
        }
        // bank order, whatever the file order
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        QuestionBank.GENRE_BUCKETS.forEach(b -> ordered.put(b, buckets.get(b)));
        return new GenreMatcher(version, ordered, keywords(root.get("exclude_phrases"), "exclude_phrases"),
                keywords(root.get("local_events"), "local_events"));
    }

    /** NFD, accents stripped, lower case, every run of non-alphanumerics one space, trimmed. */
    public static String normalise(String raw) {
        if (raw == null) return "";
        String stripped = MARKS.matcher(Normalizer.normalize(raw, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALNUM.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    public int version() {
        return version;
    }

    /** Normalised keywords per bucket, in bank order. */
    public Map<String, List<String>> buckets() {
        return buckets;
    }

    /** Title and each keyword are matched separately, so a phrase never spans two of them. */
    public Match match(String title, List<String> keywords) {
        List<String> texts = new ArrayList<>();
        texts.add(" " + normalise(title) + " ");
        if (keywords != null) keywords.forEach(k -> texts.add(" " + normalise(k) + " "));
        boolean community = anyHit(texts, localEvents);
        if (anyHit(texts, excludePhrases)) return new Match(Set.of(), community);
        Set<String> genres = new LinkedHashSet<>();
        buckets.forEach((bucket, words) -> {
            if (anyHit(texts, words)) genres.add(bucket);
        });
        return new Match(genres, community);
    }

    private static boolean anyHit(List<String> texts, List<String> phrases) {
        for (String text : texts) {
            for (String p : phrases) {
                if (text.contains(" " + p + " ")) return true;
            }
        }
        return false;
    }

    private static List<String> keywords(Object raw, String where) {
        if (!(raw instanceof List<?> list)) throw new IllegalStateException(FILE + " " + where + ": expected a list");
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            String k = o == null ? "" : String.valueOf(o);
            if (!k.equals(k.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(FILE + " " + where + ": '" + k + "' must be lowercase");
            }
            String stripped = MARKS.matcher(Normalizer.normalize(k, Normalizer.Form.NFD)).replaceAll("");
            if (!stripped.chars().allMatch(c -> c < 128)) {
                throw new IllegalStateException(FILE + " " + where + ": '" + k + "' is not ASCII after accent stripping");
            }
            if (k.strip().length() < 2 || k.strip().length() > 64) {
                throw new IllegalStateException(FILE + " " + where + ": '" + k + "' must be 2-64 characters");
            }
            String n = normalise(k);
            if (n.isEmpty()) throw new IllegalStateException(FILE + " " + where + ": '" + k + "' has no letter or digit");
            if (out.contains(n)) throw new IllegalStateException(FILE + " " + where + ": duplicate keyword '" + k + "'");
            out.add(n);
        }
        return List.copyOf(out);
    }
}
