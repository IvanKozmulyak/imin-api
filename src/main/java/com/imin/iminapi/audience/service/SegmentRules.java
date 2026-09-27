package com.imin.iminapi.audience.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.dto.SegmentRule;
import com.imin.iminapi.audience.dto.SegmentRuleGroup;
import com.imin.iminapi.util.EventNormalization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The segment rule grammar stored in {@code segments.rules_json}.
 *
 * <p>Two shapes: a legacy array of {@code {field, operator, value}} rules (one AND group), or
 * {@code {"groups": [{"combinator": "and"|"or"|"not", "rules": [...]}]}}. Groups are ANDed together;
 * {@code and} needs every rule, {@code or} at least one, {@code not} none of them. A group without rules
 * is neutral, and no groups at all matches everyone.
 */
public final class SegmentRules {

    public enum Combinator {
        AND, OR, NOT;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Combinator parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "and" -> AND;
                case "or" -> OR;
                case "not" -> NOT;
                default -> null;
            };
        }
    }

    public record Rule(String field, String operator, String value) {}

    public record Group(Combinator combinator, List<Rule> rules) {}

    /** Parsed rules; no groups means everyone. {@code legacy} is true for the flat-array shape. */
    public record Parsed(List<Group> groups, boolean legacy) {

        public boolean everyone() {
            return groups.stream().allMatch(g -> g.rules().isEmpty());
        }

        public Set<String> fields() {
            Set<String> out = new LinkedHashSet<>();
            groups.forEach(g -> g.rules().forEach(r -> out.add(r.field())));
            return out;
        }

        public List<Rule> rulesOn(String field) {
            List<Rule> out = new ArrayList<>();
            groups.forEach(g -> g.rules().forEach(r -> {
                if (field.equals(r.field())) out.add(r);
            }));
            return out;
        }

        public List<SegmentRuleGroup> asDto() {
            return groups.stream().map(g -> new SegmentRuleGroup(g.combinator().key(),
                    g.rules().stream().map(r -> new SegmentRule(r.field(), r.operator(), r.value())).toList()))
                    .toList();
        }
    }

    public static final Set<String> NUMERIC_FIELDS = Set.of("events", "spend_minor", "recency", "no_show", "nps");
    /** Legacy string fields: any comparison operator is accepted and means equality. */
    public static final Set<String> STRING_FIELDS = Set.of("lifecycle", "consent_status", "consent_basis");
    /** Fields read from fan features and tickets; they take {@code ==} or {@code in}. */
    public static final Set<String> SET_FIELDS = Set.of("guest_class", "genre", "city", "attended_event");
    public static final Set<String> FAN_FEATURE_FIELDS = Set.of("guest_class", "genre", "city");

    public static final Set<String> OPERATORS = Set.of(">=", "<=", ">", "<", "==");
    public static final Set<String> SET_OPERATORS = Set.of("==", "in");

    public static final Set<String> GUEST_CLASSES =
            Set.of("loyal", "repeat", "first_timer", "lapsing", "dormant", "imported", "none");

    static final int MAX_GROUPS = 10;
    static final int MAX_RULES_PER_GROUP = 20;
    static final int MAX_VALUES = 50;
    static final int MAX_VALUE_LENGTH = 200;
    // ponytail: a flat cap on the serialized rules, checked before parsing; ~3x what the editor's limits produce in practice.
    static final int MAX_JSON_LENGTH = 65_536;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SegmentRules() {}

    /** Blank = everyone; {@code null} = unreadable (the caller matches nobody). */
    public static Parsed parse(String json) {
        if (json == null || json.isBlank()) return new Parsed(List.of(), true);
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
        if (root == null) return null;
        if (root.isArray()) {
            List<Rule> rules = rules(root);
            return rules == null ? null : new Parsed(List.of(new Group(Combinator.AND, rules)), true);
        }
        if (!root.isObject() || !root.path("groups").isArray()) return null;
        List<Group> groups = new ArrayList<>();
        for (JsonNode g : root.get("groups")) {
            if (!g.isObject()) return null;
            Combinator c = Combinator.parse(g.path("combinator").isTextual() ? g.get("combinator").asText() : null);
            if (c == null) return null;
            JsonNode rulesNode = g.path("rules");
            List<Rule> rules = rulesNode.isMissingNode() || rulesNode.isNull() ? List.of() : rules(rulesNode);
            if (rules == null) return null;
            groups.add(new Group(c, rules));
        }
        return new Parsed(List.copyOf(groups), false);
    }

    private static List<Rule> rules(JsonNode array) {
        if (!array.isArray()) return null;
        List<Rule> out = new ArrayList<>();
        for (JsonNode r : array) {
            if (!r.isObject()) return null;
            out.add(new Rule(text(r, "field"), text(r, "operator"), text(r, "value")));
        }
        return List.copyOf(out);
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    /**
     * First problem with {@code json}, or {@code null} when the engine can run it. {@code genreBuckets} is
     * the genre whitelist; {@code foreignEvents} returns the given event ids that are not this org's.
     */
    public static String problem(String json, Set<String> genreBuckets,
                                 Function<Set<UUID>, Set<UUID>> foreignEvents) {
        if (json == null || json.isBlank()) return null;
        if (json.length() > MAX_JSON_LENGTH) return "is longer than " + MAX_JSON_LENGTH + " characters";
        Parsed parsed = parse(json);
        if (parsed == null) {
            return "must be a JSON array of {field, operator, value} rules or {\"groups\": [{combinator, rules}]}";
        }
        if (parsed.groups().size() > MAX_GROUPS) return "has more than " + MAX_GROUPS + " groups";
        Set<UUID> eventIds = new LinkedHashSet<>();
        int n = 0;
        for (Group g : parsed.groups()) {
            if (g.rules().size() > MAX_RULES_PER_GROUP) return "a group has more than " + MAX_RULES_PER_GROUP + " rules";
            for (Rule r : g.rules()) {
                n++;
                String p = ruleProblem(n, r, genreBuckets, eventIds);
                if (p != null) return p;
            }
        }
        if (!eventIds.isEmpty()) {
            Set<UUID> foreign = foreignEvents.apply(eventIds);
            if (foreign != null && !foreign.isEmpty()) {
                return "attended_event names an event that is not yours: " + foreign.iterator().next();
            }
        }
        return null;
    }

    private static String ruleProblem(int n, Rule r, Set<String> genreBuckets, Set<UUID> eventIds) {
        String field = r.field();
        String op = r.operator();
        String val = r.value();
        if (field == null || field.isBlank()) return "rule " + n + " is missing a field";
        boolean numeric = NUMERIC_FIELDS.contains(field);
        boolean set = SET_FIELDS.contains(field);
        if (!numeric && !set && !STRING_FIELDS.contains(field)) {
            return "rule " + n + " uses an unknown field '" + field + "'";
        }
        if (op == null || !(set ? SET_OPERATORS : OPERATORS).contains(op)) {
            return "rule " + n + " uses an unsupported operator '" + op + "'";
        }
        if (val == null || val.isBlank()) return "rule " + n + " is missing a value";
        if (numeric) {
            try {
                Long.parseLong(val.trim());
            } catch (NumberFormatException nfe) {
                return "rule " + n + " on '" + field + "' needs a numeric value";
            }
            return null;
        }
        if (!set) return null;
        List<String> values = values(r);
        if (values.isEmpty()) return "rule " + n + " is missing a value";
        if (values.size() > MAX_VALUES) return "rule " + n + " lists more than " + MAX_VALUES + " values";
        for (String v : values) {
            if (v.length() > MAX_VALUE_LENGTH) return "rule " + n + " has a value that is too long";
            switch (field) {
                case "guest_class" -> {
                    if (!GUEST_CLASSES.contains(v)) {
                        return "rule " + n + " guest_class must be one of " + String.join(", ", GUEST_CLASSES.stream().sorted().toList());
                    }
                }
                case "genre" -> {
                    if (genreBuckets != null && !genreBuckets.contains(v)) return "rule " + n + " genre must be one of the 8 genre buckets";
                }
                case "attended_event" -> {
                    try {
                        UUID id = UUID.fromString(v);
                        if (eventIds != null) eventIds.add(id);
                    } catch (IllegalArgumentException e) {
                        return "rule " + n + " attended_event needs an event id";
                    }
                }
                default -> { }
            }
        }
        return null;
    }

    /**
     * True when every rule is one the engine can run. A rule it cannot run is false everywhere, which a
     * {@code not} group would turn into "everyone", so the caller treats the whole set as unreadable.
     */
    public static boolean runnable(Parsed parsed) {
        int n = 0;
        for (Group g : parsed.groups()) {
            for (Rule r : g.rules()) {
                if (ruleProblem(++n, r, null, null) != null) return false;
            }
        }
        return true;
    }

    /** The rule's values: comma-separated for {@code in}, the whole trimmed value otherwise. */
    public static List<String> values(Rule r) {
        if (r.value() == null) return List.of();
        if (!"in".equals(r.operator())) {
            String v = r.value().trim();
            return v.isEmpty() ? List.of() : List.of(v);
        }
        return Arrays.stream(r.value().split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    public static boolean matches(Parsed parsed, SegmentRuleRow row, SegmentFacts facts) {
        for (Group g : parsed.groups()) {
            if (!groupMatches(g, row, facts)) return false;
        }
        return true;
    }

    private static boolean groupMatches(Group g, SegmentRuleRow row, SegmentFacts facts) {
        if (g.rules().isEmpty()) return true;
        return switch (g.combinator()) {
            case AND -> g.rules().stream().allMatch(r -> ruleMatches(r, row, facts));
            case OR -> g.rules().stream().anyMatch(r -> ruleMatches(r, row, facts));
            case NOT -> g.rules().stream().noneMatch(r -> ruleMatches(r, row, facts));
        };
    }

    /**
     * Numeric or string comparison is decided by the FIELD, not by whether the value parses as a long:
     * an unknown field or a non-numeric value on a numeric field matches nobody.
     */
    static boolean ruleMatches(Rule r, SegmentRuleRow row, SegmentFacts facts) {
        String field = r.field();
        String op = r.operator();
        String val = r.value();
        if (field == null || op == null || val == null) return false;
        if (SET_FIELDS.contains(field)) return setRuleMatches(r, row, facts);
        if (STRING_FIELDS.contains(field)) {
            String actual = switch (field) {
                case "lifecycle" -> row.lifecycle();
                case "consent_status" -> row.consentStatus();
                case "consent_basis" -> row.consentBasis();
                default -> null;
            };
            return val.equals(actual);
        }
        if (!NUMERIC_FIELDS.contains(field)) return false;
        long v;
        try {
            v = Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long actual = switch (field) {
            case "events" -> row.events();
            case "spend_minor" -> row.spendMinor();
            case "recency" -> row.recencyDays() == null ? Long.MAX_VALUE : row.recencyDays();
            case "no_show" -> row.noShow();
            case "nps" -> row.nps() == null ? Long.MIN_VALUE : row.nps();
            default -> 0;
        };
        return switch (op) {
            case ">=" -> actual >= v;
            case "<=" -> actual <= v;
            case ">" -> actual > v;
            case "<" -> actual < v;
            case "==" -> actual == v;
            default -> false;
        };
    }

    private static boolean setRuleMatches(Rule r, SegmentRuleRow row, SegmentFacts facts) {
        if (!SET_OPERATORS.contains(r.operator())) return false;
        List<String> wanted = values(r);
        if (wanted.isEmpty()) return false;
        return switch (r.field()) {
            case "guest_class" -> wanted.contains(facts.guestClass(row.membershipId()));
            case "genre" -> intersects(facts.genres(row.membershipId()), wanted);
            case "city" -> intersects(facts.cities(row.membershipId()),
                    wanted.stream().map(EventNormalization::cityKey).toList());
            case "attended_event" -> intersects(facts.attendedEvents(row.membershipId()),
                    wanted.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList());
            default -> false;
        };
    }

    private static boolean intersects(Set<String> actual, Collection<String> wanted) {
        for (String w : wanted) {
            if (actual.contains(w)) return true;
        }
        return false;
    }
}
