package com.imin.iminapi.audienceplan.config;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.CatchmentRule;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.BandPoint;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.ClassPrior;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.ClassRule;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.CoverageVerdict;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Exclusion;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Experiments;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.GenreFit;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Genres;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Legal;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Logic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.MetaAds;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Modes;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Priors;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.ProofRequirement;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.SourcedRate;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.TimingArm;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses and validates the three audience plan files. Any invalid value throws
 * {@link IllegalStateException} naming the file and key, so a bad file stops startup.
 */
public final class LogicLoader {

    private static final String UNKNOWN = "unknown";

    private LogicLoader() {}

    public static AudiencePlanLogic load(ResourceLoader resources, AudiencePlanProperties props) {
        try (InputStream logic = open(resources, props.getLogicFile());
             InputStream priors = open(resources, props.getPriorsFile());
             InputStream genres = open(resources, props.getGenresFile())) {
            return parse(logic, priors, genres);
        } catch (IOException e) {
            throw new IllegalStateException("audience plan: cannot read logic files", e);
        }
    }

    public static AudiencePlanLogic parse(InputStream logicIn, InputStream priorsIn, InputStream genresIn) {
        Logic logic = parseLogic(new Node("logic", yaml(logicIn, "logic")));
        Priors priors = parsePriors(new Node("priors", yaml(priorsIn, "priors")));
        Genres genres = parseGenres(new Node("genres", yaml(genresIn, "genres")));
        for (ClassRule rule : logic.classes()) {
            if (!priors.classes().containsKey(rule.key())) {
                throw new IllegalStateException("audience plan priors: class '" + rule.key() + "' has no prior");
            }
        }
        return new AudiencePlanLogic(logic, priors, genres);
    }

    private static InputStream open(ResourceLoader resources, String location) throws IOException {
        Resource resource = resources.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("audience plan: file not found: " + location);
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
            throw new IllegalStateException("audience plan " + file + ": invalid YAML: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException("audience plan " + file + ": expected a mapping at the root");
        }
        return map;
    }

    private static Logic parseLogic(Node root) {
        Node modes = root.map("modes");
        List<ClassRule> classes = new ArrayList<>();
        Set<String> classKeys = new HashSet<>();
        for (Node c : root.list("classes")) {
            ClassRule rule = new ClassRule(
                    c.string("key"),
                    c.optionalInt("paid_orders_min"),
                    c.optionalInt("paid_orders_max"),
                    c.optionalInt("days_since_last_paid_min"),
                    c.optionalInt("days_since_last_paid_max"),
                    c.optionalInt("days_since_last_contact_max"),
                    c.optionalBoolean("requires_import_basis"));
            if (!classKeys.add(rule.key())) {
                throw c.invalid("key", "duplicate class '" + rule.key() + "'");
            }
            requireOrdered(c, "paid_orders", rule.paidOrdersMin(), rule.paidOrdersMax());
            requireOrdered(c, "days_since_last_paid", rule.daysSinceLastPaidMin(), rule.daysSinceLastPaidMax());
            classes.add(rule);
        }
        Node verdict = root.map("coverage_verdict");
        Node experiments = root.map("experiments");
        Node legal = root.map("legal");
        Map<String, ProofRequirement> sources = new LinkedHashMap<>();
        Node sourceNode = legal.map("explicit_sources");
        for (String source : sourceNode.keys()) {
            sources.put(source, proof(sourceNode, source));
        }
        return new Logic(
                root.positiveVersion(),
                new Modes(modes.integer("warm_min_mailable"), modes.integer("hot_min_mailable")),
                root.integer("target_default_pct"),
                root.integer("min_segment_to_show"),
                root.map("taste").integer("half_life_days"),
                List.copyOf(classes),
                root.fraction("invite_other_genre_only_if_coverage_below"),
                root.enums("exclusions", Exclusion.class),
                new CoverageVerdict(verdict.enumValue("verdict_on", BandPoint.class), verdict.fraction("strong"),
                        verdict.fraction("medium")),
                new Experiments(experiments.integer("holdout_pct"), experiments.integer("holdout_min_mailable"),
                        experiments.enums("default_timing_arms", TimingArm.class)),
                new Legal(
                        legal.integer("retention_days"),
                        legal.bool("soft_opt_in_requires_paid_order_and_org_seller"),
                        legal.bool("es_robinson_check"),
                        Map.copyOf(sources),
                        Set.copyOf(legal.strings("organizer_named_text_versions")),
                        Set.copyOf(legal.strings("door_qr_text_versions"))),
                catchment(root.map("catchment")));
    }

    private static CatchmentRule catchment(Node n) {
        int radius = n.integer("radius_km");
        if (radius <= 0) {
            throw n.invalid("radius_km", "must be a positive integer");
        }
        return new CatchmentRule(radius);
    }

    private static ProofRequirement proof(Node sources, String source) {
        return sources.enumValue(source, ProofRequirement.class);
    }

    private static void requireOrdered(Node n, String prefix, Integer min, Integer max) {
        if (min != null && max != null && min > max) {
            throw n.invalid(prefix + "_min", "must not exceed " + prefix + "_max");
        }
    }

    private static Priors parsePriors(Node root) {
        Map<String, ClassPrior> classes = new LinkedHashMap<>();
        Node classNode = root.map("classes");
        for (String key : classNode.keys()) {
            Node c = classNode.map(key);
            classes.put(key, new ClassPrior(c.rateBand("purchase_rate"), c.rateBand("unsub_per_send")));
        }
        Node modifiers = root.map("modifiers");
        Node genreFit = modifiers.map("genre_fit");
        Node showUp = root.map("show_up");
        Node reach = root.map("reach_to_ticket");
        Node meta = reach.map("meta_ads");
        Map<String, SourcedRate> tribe = new LinkedHashMap<>();
        Node tribeNode = root.map("tribe_size");
        for (String key : tribeNode.keys()) {
            tribe.put(key, sourcedRate(tribeNode.map(key)));
        }
        return new Priors(
                root.positiveVersion(),
                Map.copyOf(classes),
                root.integer("prior_strength_invitations"),
                new GenreFit(genreFit.fraction("same"), genreFit.fraction("adjacent"), genreFit.fraction("other"),
                        unknownFit(genreFit)),
                modifiers.rateBand("no_show_before"),
                modifiers.rateBand("no_show_show_up_if_buy"),
                root.positiveBand("tickets_per_order"),
                showUp.rateBand("paid"),
                showUp.rateBand("free_rsvp"),
                new MetaAds(meta.fraction("ctr"), meta.rateBand("landing_to_ticket"), meta.bool("verified"),
                        meta.string("note")),
                reach.optionalRateBand("instagram_organic"),
                Map.copyOf(tribe));
    }

    /** A member without taste is scored, never zeroed out. */
    private static double unknownFit(Node genreFit) {
        double v = genreFit.fraction("unknown");
        if (v <= 0) {
            throw genreFit.invalid("unknown", "must be greater than 0");
        }
        return v;
    }

    private static SourcedRate sourcedRate(Node n) {
        double low = n.fraction("low");
        double high = n.fraction("high");
        if (low > high) {
            throw n.invalid("low", "must not exceed high");
        }
        String source = n.string("source");
        if (source.isBlank()) {
            throw n.invalid("source", "must not be blank");
        }
        boolean derived = n.optionalBoolean("derived");
        if (derived) {
            // Our own computation from the source, so there is no published year to cite.
            if (n.map().get("year") != null) {
                throw n.invalid("year", "must be absent on a derived rate");
            }
            String note = n.string("note");
            if (note.isBlank()) {
                throw n.invalid("note", "must not be blank");
            }
            return new SourcedRate(low, high, source, null, true, note);
        }
        int year = n.integer("year");
        if (year <= 0) {
            throw n.invalid("year", "must be positive");
        }
        return new SourcedRate(low, high, source, year, false, null);
    }

    private static Genres parseGenres(Node root) {
        List<String> whitelist = root.strings("whitelist");
        List<String> forbidden = root.strings("forbidden_terms");
        for (String key : whitelist) {
            for (String term : forbidden) {
                if (key.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) {
                    throw root.invalid("whitelist", "'" + key + "' contains forbidden term '" + term + "'");
                }
            }
        }
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (Object pairRaw : root.rawList("adjacency")) {
            if (!(pairRaw instanceof List<?> pair) || pair.size() != 2) {
                throw root.invalid("adjacency", "each entry must be a pair of genre keys");
            }
            if (!(pair.get(0) instanceof String a) || !(pair.get(1) instanceof String b)) {
                throw root.invalid("adjacency", "each entry must be a pair of genre keys");
            }
            for (String key : List.of(a, b)) {
                if (!whitelist.contains(key)) {
                    throw root.invalid("adjacency", "'" + key + "' is not a whitelisted genre");
                }
            }
            adjacency.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
            adjacency.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        adjacency.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        return new Genres(root.positiveVersion(), List.copyOf(whitelist), Map.copyOf(frozen), List.copyOf(forbidden));
    }

    /** A YAML mapping plus its dotted path, so every error names the offending key. */
    private record Node(String path, Map<?, ?> map) {

        Object required(String key) {
            if (!map.containsKey(key) || map.get(key) == null) {
                throw invalid(key, "is missing");
            }
            return map.get(key);
        }

        IllegalStateException invalid(String key, String problem) {
            return new IllegalStateException("audience plan " + path + "." + key + ": " + problem);
        }

        Node map(String key) {
            if (!(required(key) instanceof Map<?, ?> m)) {
                throw invalid(key, "expected a mapping");
            }
            return new Node(path + "." + key, m);
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
                out.add(new Node(path + "." + key + "[" + i + "]", m));
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

        private <E extends Enum<E>> E toEnum(String key, String raw, Class<E> type) {
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
            return map.get(key) != null && bool(key);
        }

        int integer(String key) {
            if (!(required(key) instanceof Integer i)) {
                throw invalid(key, "expected an integer");
            }
            return i;
        }

        Integer optionalInt(String key) {
            return map.get(key) == null ? null : integer(key);
        }

        int positiveVersion() {
            int v = integer("version");
            if (v <= 0) {
                throw invalid("version", "must be a positive integer");
            }
            return v;
        }

        double number(String key) {
            if (!(required(key) instanceof Number n)) {
                throw invalid(key, "expected a number");
            }
            return n.doubleValue();
        }

        double fraction(String key) {
            double v = number(key);
            if (v < 0 || v > 1) {
                throw invalid(key, "must be between 0 and 1");
            }
            return v;
        }

        /** A rate: {@code 0 <= low <= mid <= high <= 1}. */
        Band rateBand(String key) {
            Band band = band(key);
            if (band.low() < 0 || band.high() > 1) {
                throw invalid(key, "must be between 0 and 1");
            }
            return band;
        }

        /** A positive quantity with no upper bound, such as tickets per order. */
        Band positiveBand(String key) {
            Band band = band(key);
            if (band.low() <= 0) {
                throw invalid(key, "must be positive");
            }
            return band;
        }

        Optional<Band> optionalRateBand(String key) {
            return UNKNOWN.equals(required(key)) ? Optional.empty() : Optional.of(rateBand(key));
        }

        private Band band(String key) {
            if (!(required(key) instanceof List<?> l) || l.size() != 3
                    || !l.stream().allMatch(Number.class::isInstance)) {
                throw invalid(key, "expected [low, mid, high]");
            }
            Band band = new Band(((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(),
                    ((Number) l.get(2)).doubleValue());
            if (band.low() > band.mid() || band.mid() > band.high()) {
                throw invalid(key, "must satisfy low <= mid <= high");
            }
            return band;
        }
    }
}
