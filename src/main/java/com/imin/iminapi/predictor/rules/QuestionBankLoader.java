package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.QuestionBank.Action;
import com.imin.iminapi.predictor.rules.QuestionBank.GenreProfile;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.ProfileField;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.rules.QuestionBank.Thresholds;
import com.imin.iminapi.predictor.rules.QuestionBank.Window;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and validates the question bank and genre profiles. Any invalid value throws
 * {@link IllegalStateException} naming the file and dotted key, so a bad file stops startup.
 */
public final class QuestionBankLoader {

    public static final String BANK_LOCATION = "classpath:predictor/question-bank-v2.yaml";
    public static final String PROFILES_LOCATION = "classpath:predictor/genre-profiles-v1.yaml";

    private static final String BANK = "question-bank";
    private static final String PROFILES = "genre-profiles";
    private static final Pattern ID = Pattern.compile("^\\d{1,2}\\.\\d{1,2}$");
    private static final Pattern ACTION_KEY = Pattern.compile("^predictor\\.a\\.[a-z0-9_]+$");
    private static final Pattern DUE = Pattern.compile("^D-(\\d+)$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final int DEFAULT_MAX_STRENGTH = 3;
    private static final int CAPPED_MAX_STRENGTH = 2;
    private static final Set<String> THRESHOLD_KEYS =
            Set.of("adjust_min_risk", "move_min_risk", "min_coverage", "max_points_per_finding");
    private static final Set<String> QUESTION_KEYS = Set.of("id", "family", "star", "source", "kinds", "weight",
            "max_strength", "window", "stop_factor", "applies_when", "params", "template", "actions");
    private static final Set<String> APPLIES_WHEN_KEYS = Set.of("countries", "cities");
    private static final Set<String> ACTION_KEYS = Set.of("key", "when", "due");
    private static final Set<String> BUCKET_KEYS = Set.of("sub_genres", "audience_age", "communities",
            "typical_price_eur", "typical_start_hour", "buying_lead_days");
    private static final Set<String> FIELD_KEYS = Set.of("value", "sourced", "estimate");

    private QuestionBankLoader() {}

    public static QuestionBank load(ResourceLoader resources) {
        try (InputStream bank = open(resources, BANK_LOCATION);
             InputStream profiles = open(resources, PROFILES_LOCATION)) {
            return parse(bank, profiles);
        } catch (IOException e) {
            throw new IllegalStateException("predictor question bank: cannot read files", e);
        }
    }

    public static QuestionBank parse(InputStream bankIn, InputStream profilesIn) {
        Node bank = new Node(BANK, "", yaml(bankIn, BANK));
        bank.onlyKeys(Set.of("version", "thresholds", "questions"));
        int bankVersion = bank.positiveVersion();
        Thresholds thresholds = thresholds(bank.map("thresholds"));
        List<Question> questions = questions(bank);
        Node profiles = new Node(PROFILES, "", yaml(profilesIn, PROFILES));
        profiles.onlyKeys(Set.of("version", "buckets"));
        int profilesVersion = profiles.positiveVersion();
        Map<String, GenreProfile> buckets = profiles(profiles.map("buckets"));
        return new QuestionBank(bankVersion, profilesVersion, thresholds, questions, buckets);
    }

    private static InputStream open(ResourceLoader resources, String location) throws IOException {
        Resource resource = resources.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("predictor question bank: file not found: " + location);
        }
        return resource.getInputStream();
    }

    private static Map<?, ?> yaml(InputStream in, String file) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object root;
        try {
            root = new Yaml(new SafeConstructor(options)).load(in);
        } catch (YAMLException e) {
            throw new IllegalStateException("predictor " + file + ": invalid YAML: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException("predictor " + file + ": expected a mapping at the root");
        }
        return map;
    }

    private static Thresholds thresholds(Node n) {
        n.onlyKeys(THRESHOLD_KEYS);
        int adjust = n.integer("adjust_min_risk");
        int move = n.integer("move_min_risk");
        double coverage = n.number("min_coverage");
        int maxPoints = n.integer("max_points_per_finding");
        if (adjust >= move) {
            throw n.invalid("adjust_min_risk", "must be below move_min_risk");
        }
        if (coverage <= 0 || coverage > 1) {
            throw n.invalid("min_coverage", "must be above 0 and at most 1");
        }
        if (maxPoints < 1 || maxPoints > 9) {
            throw n.invalid("max_points_per_finding", "must be between 1 and 9");
        }
        return new Thresholds(adjust, move, coverage, maxPoints);
    }

    private static List<Question> questions(Node root) {
        List<Node> nodes = root.list("questions");
        if (nodes.isEmpty()) {
            throw root.invalid("questions", "must not be empty");
        }
        List<Question> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Node q : nodes) {
            q.onlyKeys(QUESTION_KEYS);
            SourceKind source = source(q);
            String id = q.string("id");
            if (!ID.matcher(id).matches()) {
                throw q.invalid("id", "'" + id + "' must look like 4.1");
            }
            if (!seen.add(id + "|" + source)) {
                throw q.invalid("id", "duplicate '" + id + "' for source " + source.name().toLowerCase(Locale.ROOT));
            }
            Set<Kind> kinds = EnumSet.noneOf(Kind.class);
            kinds.addAll(q.enums("kinds", Kind.class));
            if (kinds.isEmpty()) {
                throw q.invalid("kinds", "must not be empty");
            }
            int weight = q.integer("weight");
            if (weight < 1 || weight > 3) {
                throw q.invalid("weight", "must be between 1 and 3");
            }
            int maxStrength = q.has("max_strength") ? q.integer("max_strength") : DEFAULT_MAX_STRENGTH;
            if (maxStrength < 1 || maxStrength > 3) {
                throw q.invalid("max_strength", "must be between 1 and 3");
            }
            boolean capped = source == SourceKind.WEB || source == SourceKind.ORGANIZER || source == SourceKind.INPUT;
            if (capped && maxStrength > CAPPED_MAX_STRENGTH) {
                throw q.invalid("max_strength", "must be at most 2 for a web, organizer or input source");
            }
            Window window = q.enumValue("window", Window.class);
            boolean stopFactor = q.optionalBoolean("stop_factor");
            if (stopFactor && source != SourceKind.STRUCTURED && source != SourceKind.INTERNAL) {
                throw q.invalid("stop_factor", "allowed only for a structured or internal source");
            }
            Node applies = q.map("applies_when");
            applies.onlyKeys(APPLIES_WHEN_KEYS);
            Set<String> countries = countries(applies, "countries");
            Set<String> cities = Set.of();
            if (applies.has("cities")) {
                cities = cities(applies);
            }
            String template = q.string("template");
            String expected = "predictor.q." + id.replace('.', '_');
            if (!template.equals(expected)) {
                throw q.invalid("template", "must be " + expected);
            }
            out.add(new Question(id, q.string("family"), q.optionalBoolean("star"), source,
                    Collections.unmodifiableSet(kinds), weight, maxStrength, window, stopFactor, countries, cities,
                    params(q), template, actions(q, kinds)));
        }
        return List.copyOf(out);
    }

    private static SourceKind source(Node q) {
        Object raw = q.map().get("source");
        if (raw instanceof List<?> l) {
            if (l.size() >= 2) {
                throw q.invalid("source", "has more than one source");
            }
            raw = l.isEmpty() ? null : l.get(0);
        }
        if (raw == null) {
            throw q.invalid("source", "has no source");
        }
        if (!(raw instanceof String s)) {
            throw q.invalid("source", "expected text");
        }
        return q.toEnum("source", s, SourceKind.class);
    }

    private static Set<String> countries(Node n, String key) {
        List<String> raw = n.strings(key);
        if (raw.isEmpty()) {
            throw n.invalid(key, "must not be empty");
        }
        for (String c : raw) {
            if (!COUNTRY.matcher(c).matches()) {
                throw n.invalid(key, "'" + c + "' is not an ISO-3166 alpha-2 code");
            }
        }
        return Set.copyOf(raw);
    }

    private static Set<String> cities(Node n) {
        List<String> raw = n.strings("cities");
        if (raw.isEmpty()) {
            throw n.invalid("cities", "must not be empty when present");
        }
        for (String c : raw) {
            if (c.isBlank()) {
                throw n.invalid("cities", "must not contain a blank name");
            }
        }
        return Set.copyOf(raw);
    }

    private static Map<String, Number> params(Node q) {
        if (!q.has("params")) {
            return Map.of();
        }
        Node p = q.map("params");
        Map<String, Number> out = new LinkedHashMap<>();
        for (String key : p.keys()) {
            if (!(p.map().get(key) instanceof Number n)) {
                throw p.invalid(key, "expected a number");
            }
            out.put(key, n);
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<Action> actions(Node q, Set<Kind> kinds) {
        if (!q.has("actions")) {
            return List.of();
        }
        List<Action> out = new ArrayList<>();
        for (Node a : q.list("actions")) {
            a.onlyKeys(ACTION_KEYS);
            String key = a.string("key");
            if (!ACTION_KEY.matcher(key).matches()) {
                throw a.invalid("key", "'" + key + "' must match predictor.a.<lowercase_name>");
            }
            Kind when = a.enumValue("when", Kind.class);
            if (!kinds.contains(when)) {
                throw a.invalid("when", "'" + when.name().toLowerCase(Locale.ROOT) + "' is not one of the question's kinds");
            }
            String due = a.string("due");
            var m = DUE.matcher(due);
            if (!m.matches()) {
                throw a.invalid("due", "'" + due + "' must look like D-28");
            }
            out.add(new Action(key, when, Integer.parseInt(m.group(1))));
        }
        return List.copyOf(out);
    }

    private static Map<String, GenreProfile> profiles(Node buckets) {
        for (String key : buckets.keys()) {
            if (!QuestionBank.GENRE_BUCKETS.contains(key)) {
                throw buckets.invalidSelf("unknown bucket '" + key + "'");
            }
        }
        Map<String, GenreProfile> out = new LinkedHashMap<>();
        Map<String, String> subGenreOwner = new HashMap<>();
        for (String bucket : QuestionBank.GENRE_BUCKETS) {
            if (!buckets.has(bucket)) {
                throw buckets.invalidSelf("missing bucket '" + bucket + "'");
            }
            Node b = buckets.map(bucket);
            b.onlyKeys(BUCKET_KEYS);
            List<String> subGenres = b.strings("sub_genres");
            for (String s : subGenres) {
                if (s.isBlank() || !s.equals(s.toLowerCase(Locale.ROOT))) {
                    throw b.invalid("sub_genres", "'" + s + "' must be lowercase and not blank");
                }
                String owner = subGenreOwner.putIfAbsent(s, bucket);
                if (owner != null) {
                    throw b.invalid("sub_genres", "'" + s + "' already belongs to '" + owner + "'");
                }
            }
            out.put(bucket, new GenreProfile(List.copyOf(subGenres),
                    range(b, "audience_age", 16, 80, true),
                    communities(b),
                    range(b, "typical_price_eur", 0, Integer.MAX_VALUE, false),
                    bounded(b, "typical_start_hour", 0, 23),
                    bounded(b, "buying_lead_days", 0, 120)));
        }
        return Collections.unmodifiableMap(out);
    }

    /** Reads {@code {value, sourced|estimate}} and returns the marker half; the caller reads the value. */
    private static Marker marker(Node field) {
        field.onlyKeys(FIELD_KEYS);
        boolean hasSourced = field.has("sourced");
        boolean hasEstimate = field.has("estimate");
        if (hasSourced && hasEstimate) {
            throw field.invalidSelf("has both sourced and estimate");
        }
        if (hasSourced) {
            String url = field.string("sourced");
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw field.invalid("sourced", "must be a URL");
            }
            return new Marker(url, false);
        }
        if (hasEstimate && field.bool("estimate")) {
            return new Marker(null, true);
        }
        throw field.invalidSelf("needs sourced or estimate: true");
    }

    private record Marker(String url, boolean estimate) {}

    private static ProfileField<List<Integer>> range(Node bucket, String key, int min, int max, boolean strict) {
        Node field = bucket.map(key);
        Marker marker = marker(field);
        List<?> raw = field.rawList("value");
        if (raw.size() != 2 || !(raw.get(0) instanceof Integer lo) || !(raw.get(1) instanceof Integer hi)) {
            throw field.invalid("value", "expected [low, high] integers");
        }
        boolean ordered = strict ? lo < hi : lo <= hi;
        if (lo < min || hi > max || !ordered) {
            throw field.invalid("value", "must satisfy " + min + " <= low " + (strict ? "<" : "<=") + " high"
                    + (max == Integer.MAX_VALUE ? "" : " <= " + max));
        }
        return new ProfileField<>(List.of(lo, hi), marker.url(), marker.estimate());
    }

    private static ProfileField<Integer> bounded(Node bucket, String key, int min, int max) {
        Node field = bucket.map(key);
        Marker marker = marker(field);
        int v = field.integer("value");
        if (v < min || v > max) {
            throw field.invalid("value", "must be between " + min + " and " + max);
        }
        return new ProfileField<>(v, marker.url(), marker.estimate());
    }

    private static ProfileField<List<String>> communities(Node bucket) {
        Node field = bucket.map("communities");
        Marker marker = marker(field);
        List<String> codes = field.strings("value");
        for (String c : codes) {
            if (!COUNTRY.matcher(c).matches()) {
                throw field.invalid("value", "'" + c + "' is not an ISO-3166 alpha-2 code");
            }
        }
        return new ProfileField<>(List.copyOf(codes), marker.url(), marker.estimate());
    }

    // ponytail: trimmed copy of LogicLoader.Node; sharing it would mean touching audience-plan code.
    /** A YAML mapping plus its dotted path, so every error names the file and offending key. */
    private record Node(String file, String path, Map<?, ?> map) {

        boolean has(String key) {
            return map.get(key) != null;
        }

        Object required(String key) {
            if (!has(key)) {
                throw invalid(key, "is missing");
            }
            return map.get(key);
        }

        private String at(String key) {
            return path.isEmpty() ? key : path + "." + key;
        }

        IllegalStateException invalid(String key, String problem) {
            return new IllegalStateException("predictor " + file + " " + at(key) + ": " + problem);
        }

        IllegalStateException invalidSelf(String problem) {
            return new IllegalStateException("predictor " + file + " " + path + ": " + problem);
        }

        Node map(String key) {
            if (!(required(key) instanceof Map<?, ?> m)) {
                throw invalid(key, "expected a mapping");
            }
            return new Node(file, at(key), m);
        }

        /** Rejects any key outside {@code allowed}, so a typo cannot silently widen or drop a rule. */
        void onlyKeys(Set<String> allowed) {
            for (String key : keys()) {
                if (!allowed.contains(key)) {
                    throw invalidSelf("unknown key '" + key + "'");
                }
            }
        }

        List<String> keys() {
            return map.keySet().stream().map(String::valueOf).toList();
        }

        List<?> rawList(String key) {
            if (!(required(key) instanceof List<?> l)) {
                throw invalid(key, "expected a list");
            }
            return l;
        }

        List<Node> list(String key) {
            List<Node> out = new ArrayList<>();
            List<?> raw = rawList(key);
            for (int i = 0; i < raw.size(); i++) {
                if (!(raw.get(i) instanceof Map<?, ?> m)) {
                    throw invalid(key, "entry " + i + " must be a mapping");
                }
                out.add(new Node(file, at(key) + "[" + i + "]", m));
            }
            return out;
        }

        List<String> strings(String key) {
            List<?> raw = rawList(key);
            for (int i = 0; i < raw.size(); i++) {
                if (!(raw.get(i) instanceof String)) {
                    throw invalid(key, "entry " + i + " must be text");
                }
            }
            return raw.stream().map(String.class::cast).toList();
        }

        String string(String key) {
            if (!(required(key) instanceof String s)) {
                throw invalid(key, "expected text");
            }
            return s;
        }

        <E extends Enum<E>> E enumValue(String key, Class<E> type) {
            return toEnum(key, string(key), type);
        }

        <E extends Enum<E>> List<E> enums(String key, Class<E> type) {
            return strings(key).stream().map(v -> toEnum(key, v, type)).toList();
        }

        <E extends Enum<E>> E toEnum(String key, String raw, Class<E> type) {
            try {
                return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw invalid(key, "unknown value '" + raw + "'");
            }
        }

        boolean bool(String key) {
            if (!(required(key) instanceof Boolean b)) {
                throw invalid(key, "expected true or false");
            }
            return b;
        }

        boolean optionalBoolean(String key) {
            return has(key) && bool(key);
        }

        int integer(String key) {
            if (!(required(key) instanceof Integer i)) {
                throw invalid(key, "expected an integer");
            }
            return i;
        }

        double number(String key) {
            if (!(required(key) instanceof Number n)) {
                throw invalid(key, "expected a number");
            }
            return n.doubleValue();
        }

        int positiveVersion() {
            int v = integer("version");
            if (v <= 0) {
                throw invalid("version", "must be a positive integer");
            }
            return v;
        }
    }
}
