package com.imin.iminapi.audience.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.dto.SegmentDraftLlm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The segment rule schema — the single source of truth for which {@code {field, operator, value}}
 * shapes {@code SegmentService}'s engine can actually evaluate (see {@code SegmentService.matchRule}).
 *
 * <p>The AI drafting path validates every model-proposed rule against this before it can become a
 * segment: unknown fields, unsupported operators and malformed values are STRIPPED and reported as
 * "unsupported", never silently persisted. By construction the model cannot produce a rule the
 * engine can't run — there is no free-form SQL/query path.
 *
 * <p>Supported fields (exactly what {@code SegmentService.matchRule} reads):
 * <ul>
 *   <li><b>Numeric</b> (operators {@code >=, <=, >, <, ==}; integer value):
 *       {@code events}, {@code spend_minor} (cents), {@code recency} (days since last purchase),
 *       {@code no_show}, {@code nps}</li>
 *   <li><b>Enum</b> (equality only): {@code lifecycle}, {@code consent_status}, {@code consent_basis}</li>
 * </ul>
 * Rules are conjunctive (AND). An empty rule list matches everyone.
 */
public final class SegmentRuleSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Numeric fields the engine reads as longs. */
    static final Set<String> NUMERIC_FIELDS = Set.of("events", "spend_minor", "recency", "no_show", "nps");

    /** Enum fields the engine compares by equality, mapped to their allowed values. */
    static final Map<String, Set<String>> ENUM_VALUES = Map.of(
            "lifecycle", Set.of("prospect", "firsttime", "repeat", "vip", "lapsing", "dormant", "wonback"),
            "consent_status", Set.of("never", "subscribed", "unsubscribed"),
            "consent_basis", Set.of("explicit", "soft_opt_in"));

    static final Set<String> NUMERIC_OPS = Set.of(">=", "<=", ">", "<", "==");

    /** Abuse/sanity cap on how many rules one draft may carry. */
    private static final int MAX_RULES = 12;

    /** A rule that has passed validation and is safe to hand to the engine. */
    public record ValidRule(String field, String operator, String value) {
        Map<String, String> asMap() {
            return Map.of("field", field, "operator", operator, "value", value);
        }
    }

    /** Validation outcome: the accepted rules plus a human reason for each dropped part. */
    public record Result(List<ValidRule> rules, List<String> unsupported) {}

    private SegmentRuleSchema() {}

    /**
     * Validate the model's proposed rules against the engine schema. Returns the accepted rules
     * (deduplicated, capped at {@link #MAX_RULES}) plus a human-readable reason for every part
     * that was dropped. Tolerant of nulls throughout — untrusted model output.
     */
    public static Result validate(List<SegmentDraftLlm.Rule> raw) {
        List<ValidRule> valid = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (raw == null) return new Result(valid, unsupported);

        for (SegmentDraftLlm.Rule r : raw) {
            if (r == null) continue;
            String field = r.field() == null ? "" : r.field().trim().toLowerCase(Locale.ROOT);
            String rawOp = r.operator() == null ? "" : r.operator().trim();
            String rawVal = r.value() == null ? "" : r.value().trim();

            if (field.isEmpty()) {
                unsupported.add("An empty filter was ignored");
                continue;
            }

            if (NUMERIC_FIELDS.contains(field)) {
                String op = canonicalOp(rawOp);
                if (op == null || !NUMERIC_OPS.contains(op)) {
                    unsupported.add("\"" + describe(field) + "\" with operator \"" + rawOp + "\" isn't supported");
                    continue;
                }
                long parsed;
                try {
                    parsed = Long.parseLong(stripNumeric(rawVal));
                } catch (NumberFormatException e) {
                    unsupported.add("\"" + describe(field) + "\" needs a whole number, got \"" + rawVal + "\"");
                    continue;
                }
                addIfNew(valid, seen, unsupported, new ValidRule(field, op, Long.toString(parsed)));
            } else if (ENUM_VALUES.containsKey(field)) {
                // Enum fields support equality ONLY. An omitted operator is treated as equality;
                // anything that does NOT canonicalize to "==" (including negation like "!=" / "not")
                // is unsupported — the engine cannot express it.
                boolean equalityIntended = rawOp.isEmpty() || "==".equals(canonicalOp(rawOp));
                if (!equalityIntended) {
                    unsupported.add("\"" + describe(field) + "\" only supports equality (is), not \"" + rawOp + "\"");
                    continue;
                }
                String val = rawVal.toLowerCase(Locale.ROOT);
                Set<String> allowed = ENUM_VALUES.get(field);
                if (!allowed.contains(val)) {
                    unsupported.add("\"" + describe(field) + "\" must be one of " + String.join(", ", new TreeSet<>(allowed)));
                    continue;
                }
                addIfNew(valid, seen, unsupported, new ValidRule(field, "==", val));
            } else {
                String shown = r.field() == null ? "" : r.field().trim();
                unsupported.add("\"" + shown + "\" isn't a filterable attribute");
            }
        }
        return new Result(valid, unsupported);
    }

    private static void addIfNew(List<ValidRule> valid, Set<String> seen,
                                 List<String> unsupported, ValidRule rule) {
        String key = rule.field() + "|" + rule.operator() + "|" + rule.value();
        if (!seen.add(key)) return; // duplicate — silently drop
        if (valid.size() >= MAX_RULES) {
            if (unsupported.stream().noneMatch(u -> u.startsWith("Too many filters"))) {
                unsupported.add("Too many filters — extra conditions were dropped");
            }
            return;
        }
        valid.add(rule);
    }

    /** Canonical rules JSON for persistence — the exact string sent to POST /audience/segments. */
    public static String canonicalJson(List<ValidRule> rules) {
        try {
            return MAPPER.writeValueAsString(asMaps(rules));
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /** The validated rules as plain maps, for rendering in the response. */
    public static List<Map<String, String>> asMaps(List<ValidRule> rules) {
        return rules.stream().map(ValidRule::asMap).toList();
    }

    /** Map operator synonyms to the engine's symbols; null when unrecognizable. */
    static String canonicalOp(String op) {
        if (op == null) return null;
        String o = op.trim().toLowerCase(Locale.ROOT);
        return switch (o) {
            case ">=", "gte", "≥", "at_least", "greater_than_or_equal", "greater_or_equal" -> ">=";
            case "<=", "lte", "≤", "at_most", "less_than_or_equal", "less_or_equal" -> "<=";
            case ">", "gt", "greater_than", "greater", "over", "more_than" -> ">";
            case "<", "lt", "less_than", "less", "under", "fewer_than" -> "<";
            case "==", "=", "eq", "equals", "equal", "is" -> "==";
            default -> null;
        };
    }

    /** Strip currency symbols and thousands separators so "€100"/"10,000" parse; decimals still reject. */
    private static String stripNumeric(String v) {
        return v.replaceAll("[€$£,_\\s]", "");
    }

    /** Deterministic, human-readable sentence for a validated rule (one filter per sentence). */
    public static String explain(ValidRule r) {
        String f = r.field();
        String op = r.operator();
        String v = r.value();
        return switch (f) {
            case "events" -> "Attended " + countPhrase(op, v) + " " + plural(v, "event") + ".";
            case "spend_minor" -> "Spent " + moneyPhrase(op, v) + " in total.";
            case "recency" -> recencyPhrase(op, v);
            case "no_show" -> "Was a no-show for " + countPhrase(op, v) + " " + plural(v, "event") + ".";
            case "nps" -> "Gave an NPS score " + countPhrase(op, v) + ".";
            case "lifecycle" -> "Lifecycle stage is " + capitalize(v) + ".";
            case "consent_status" -> "Subscription status is " + v + ".";
            case "consent_basis" -> "Consent basis is " + v.replace('_', ' ') + ".";
            default -> describe(f) + " " + op + " " + v + ".";
        };
    }

    /** Human field noun for messages. */
    static String describe(String field) {
        return switch (field) {
            case "events" -> "events attended";
            case "spend_minor" -> "total spend";
            case "recency" -> "days since last purchase";
            case "no_show" -> "no-shows";
            case "nps" -> "NPS score";
            case "lifecycle" -> "lifecycle stage";
            case "consent_status" -> "subscription status";
            case "consent_basis" -> "consent basis";
            default -> field;
        };
    }

    private static String countPhrase(String op, String value) {
        String word = switch (op) {
            case ">=" -> "at least";
            case "<=" -> "at most";
            case ">" -> "more than";
            case "<" -> "fewer than";
            case "==" -> "exactly";
            default -> op;
        };
        return word + " " + value;
    }

    private static String moneyPhrase(String op, String centsStr) {
        String money = money(centsStr);
        String word = switch (op) {
            case ">=" -> "at least";
            case "<=" -> "at most";
            case ">" -> "more than";
            case "<" -> "less than";
            case "==" -> "exactly";
            default -> op;
        };
        return word + " " + money;
    }

    private static String recencyPhrase(String op, String v) {
        return switch (op) {
            case "<=", "<" -> "Purchased within the last " + v + " days.";
            case ">=", ">" -> "Hasn't purchased in " + v + "+ days.";
            case "==" -> "Last purchased exactly " + v + " days ago.";
            default -> "Days since last purchase " + op + " " + v + ".";
        };
    }

    /** "€100" for whole euros, "€99.99" otherwise. Input is minor units (cents). */
    static String money(String centsStr) {
        long cents;
        try {
            cents = Long.parseLong(centsStr);
        } catch (NumberFormatException e) {
            return "€" + centsStr;
        }
        if (cents % 100 == 0) return "€" + (cents / 100);
        return String.format(Locale.ROOT, "€%.2f", cents / 100.0);
    }

    private static String plural(String value, String noun) {
        return "1".equals(value) ? noun : noun + "s";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
