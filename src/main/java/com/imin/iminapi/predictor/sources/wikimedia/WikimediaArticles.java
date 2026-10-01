package com.imin.iminapi.predictor.sources.wikimedia;

import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBank.GenreProfile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which Wikipedia article stands for a genre bucket or sub-genre in a country's language, from
 * {@code predictor/wikimedia-articles-v1.yaml}. Any invalid value throws {@link IllegalStateException}
 * naming the key, so a bad file (or a renamed sub-genre) stops boot.
 */
public record WikimediaArticles(int version, LocalDate verifiedOn, Map<String, String> languages,
                                Map<String, Bucket> buckets) {

    public static final String LOCATION = "classpath:predictor/wikimedia-articles-v1.yaml";
    /** V164 {@code wikimedia_pageviews_month.article VARCHAR(255)}. */
    public static final int MAX_TITLE = 255;
    static final Set<String> LANGUAGES = Set.of("fr", "nl", "de", "es", "uk", "en");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Set<String> ROOT_KEYS = Set.of("version", "verified_on", "languages", "buckets");
    private static final Set<String> BUCKET_KEYS = Set.of("article", "sub_genres");
    private static final String FILE = "predictor wikimedia-articles";

    /** One article on one language edition; {@code title} is canonical, with underscores. */
    public record Article(String project, String title) {
        public String language() {
            return project.substring(0, project.indexOf('.'));
        }

        /** A link to the article only; none of its text is copied. */
        public String url() {
            return "https://" + language() + ".wikipedia.org/wiki/" + WikimediaPageviewsClient.encodeTitle(title);
        }
    }

    /** Titles per language: the bucket's own (may be empty) and per sub-genre. */
    public record Bucket(Map<String, String> article, Map<String, Map<String, String>> subGenres) {
        public Bucket {
            article = Collections.unmodifiableMap(new LinkedHashMap<>(article));
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            subGenres.forEach((k, v) -> copy.put(k, Collections.unmodifiableMap(new LinkedHashMap<>(v))));
            subGenres = Collections.unmodifiableMap(copy);
        }
    }

    public WikimediaArticles {
        languages = Collections.unmodifiableMap(new LinkedHashMap<>(languages));
        buckets = Collections.unmodifiableMap(new LinkedHashMap<>(buckets));
    }

    public static WikimediaArticles load(ResourceLoader resources, QuestionBank bank) {
        Resource resource = resources.getResource(LOCATION);
        if (!resource.exists()) throw new IllegalStateException(FILE + ": file not found: " + LOCATION);
        try (InputStream in = resource.getInputStream()) {
            return parse(in, bank);
        } catch (IOException e) {
            throw new IllegalStateException(FILE + ": cannot read " + LOCATION, e);
        }
    }

    public static WikimediaArticles parse(InputStream in, QuestionBank bank) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object raw;
        try {
            raw = new Yaml(new SafeConstructor(options)).load(in);
        } catch (YAMLException e) {
            throw new IllegalStateException(FILE + ": invalid YAML: " + e.getMessage(), e);
        }
        Map<?, ?> root = mapping(raw, "root");
        onlyKeys(root, ROOT_KEYS, "root");
        if (!(root.get("version") instanceof Integer version) || version < 1) {
            throw new IllegalStateException(FILE + " version: must be an integer of at least 1");
        }
        LocalDate verifiedOn = date(root.get("verified_on"));
        Map<String, String> languages = languages(mapping(root.get("languages"), "languages"));
        Set<String> configured = new LinkedHashSet<>(languages.values());
        Map<?, ?> rawBuckets = mapping(root.get("buckets"), "buckets");
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawBuckets.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!QuestionBank.GENRE_BUCKETS.contains(name)) {
                throw new IllegalStateException(FILE + " buckets: unknown bucket '" + name + "'");
            }
            buckets.put(name, bucket(name, mapping(e.getValue(), "buckets." + name), bank.profiles().get(name), configured));
        }
        return new WikimediaArticles(version, verifiedOn, languages, buckets);
    }

    public Optional<String> language(String country) {
        return Optional.ofNullable(country == null ? null : languages.get(country.toUpperCase(Locale.ROOT)));
    }

    /** The sub-genre's article in the country's language, else the bucket's; empty when neither is mapped. */
    public Optional<Article> lookup(String country, String bucket, String subGenre) {
        Optional<String> lang = language(country);
        Bucket b = bucket == null ? null : buckets.get(bucket);
        if (lang.isEmpty() || b == null) return Optional.empty();
        String l = lang.get();
        if (subGenre != null) {
            Map<String, String> titles = b.subGenres().get(subGenre.strip().toLowerCase(Locale.ROOT));
            if (titles != null && titles.containsKey(l)) return Optional.of(new Article(l + ".wikipedia", titles.get(l)));
        }
        String title = b.article().get(l);
        return title == null ? Optional.empty() : Optional.of(new Article(l + ".wikipedia", title));
    }

    /** Every mapped article once, in file order: the sync job's input. */
    public List<Article> distinctArticles() {
        Set<Article> out = new LinkedHashSet<>();
        for (Bucket b : buckets.values()) {
            b.article().forEach((l, t) -> out.add(new Article(l + ".wikipedia", t)));
            b.subGenres().values().forEach(m -> m.forEach((l, t) -> out.add(new Article(l + ".wikipedia", t))));
        }
        return List.copyOf(out);
    }

    private static Bucket bucket(String name, Map<?, ?> m, GenreProfile profile, Set<String> configured) {
        String where = "buckets." + name;
        onlyKeys(m, BUCKET_KEYS, where);
        Map<String, String> article = m.containsKey("article")
                ? titles(mapping(m.get("article"), where + ".article"), where + ".article", configured) : Map.of();
        Map<String, Map<String, String>> subGenres = new LinkedHashMap<>();
        if (m.containsKey("sub_genres")) {
            Map<?, ?> raw = mapping(m.get("sub_genres"), where + ".sub_genres");
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                String sub = String.valueOf(e.getKey());
                String subWhere = where + ".sub_genres." + sub;
                if (profile == null || !profile.subGenres().contains(sub)) {
                    throw new IllegalStateException(FILE + " " + subWhere + ": '" + sub
                            + "' is not a sub-genre of '" + name + "' in the genre profiles");
                }
                subGenres.put(sub, titles(mapping(e.getValue(), subWhere), subWhere, configured));
            }
        }
        return new Bucket(article, subGenres);
    }

    private static Map<String, String> titles(Map<?, ?> m, String where, Set<String> configured) {
        onlyKeys(m, LANGUAGES, where);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String lang = String.valueOf(e.getKey());
            if (!configured.contains(lang)) {
                throw new IllegalStateException(FILE + " " + where + "." + lang + ": language " + lang
                        + " is not configured for any country in languages");
            }
            if (!(e.getValue() instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(FILE + " " + where + "." + lang + ": title is missing or blank");
            }
            String title = s.strip().replace(' ', '_');
            if (title.length() > MAX_TITLE) {
                throw new IllegalStateException(FILE + " " + where + "." + lang + ": title longer than " + MAX_TITLE);
            }
            out.put(lang, title);
        }
        return out;
    }

    private static Map<String, String> languages(Map<?, ?> m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String country = String.valueOf(e.getKey());
            if (!COUNTRY.matcher(country).matches()) {
                throw new IllegalStateException(FILE + " languages: '" + country + "' is not an ISO-3166 alpha-2 code");
            }
            if (!(e.getValue() instanceof String lang) || !LANGUAGES.contains(lang)) {
                throw new IllegalStateException(FILE + " languages." + country + ": unsupported language '"
                        + e.getValue() + "'");
            }
            out.put(country, lang);
        }
        return out;
    }

    // SnakeYAML reads an unquoted 2026-10-01 as a timestamp; a quoted one stays text.
    private static LocalDate date(Object raw) {
        if (raw instanceof Date d) return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        if (raw instanceof String s) {
            try {
                return LocalDate.parse(s.strip());
            } catch (DateTimeParseException e) {
                throw new IllegalStateException(FILE + " verified_on: '" + s + "' is not an ISO date");
            }
        }
        throw new IllegalStateException(FILE + " verified_on: is missing");
    }

    private static Map<?, ?> mapping(Object raw, String where) {
        if (!(raw instanceof Map<?, ?> m)) throw new IllegalStateException(FILE + " " + where + ": expected a mapping");
        return m;
    }

    private static void onlyKeys(Map<?, ?> m, Set<String> allowed, String where) {
        List<String> unknown = new ArrayList<>();
        for (Object k : m.keySet()) if (!allowed.contains(String.valueOf(k))) unknown.add(String.valueOf(k));
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(FILE + " " + where + ": unknown key '" + unknown.get(0) + "'");
        }
    }
}
