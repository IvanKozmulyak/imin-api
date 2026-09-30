package com.imin.iminapi.predictor.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** The date-check question bank plus the genre profiles it scores against, as loaded by {@link QuestionBankLoader}. */
public record QuestionBank(int bankVersion, int profilesVersion, Thresholds thresholds, List<Question> questions,
                           Map<String, GenreProfile> profiles) {

    /** The 8 event genre buckets; must equal {@code audienceplan/genres-v1.yaml} whitelist. */
    public static final List<String> GENRE_BUCKETS = List.of(
            "house & techno",
            "bass & hard dance",
            "club / open format",
            "hip-hop & r&b",
            "latin & afrobeats",
            "rock & alternative",
            "pop",
            "jazz & acoustic");

    public enum SourceKind { STRUCTURED, INTERNAL, WEB, ORGANIZER, INPUT }

    public enum Kind { RISK, OPPORTUNITY }

    public enum Window { NIGHT, WEEK, MONTH }

    public record Thresholds(int adjustMinRisk, int moveMinRisk, double minCoverage, int maxPointsPerFinding) {}

    public record Question(String id, String family, boolean star, SourceKind source, Set<Kind> kinds, int weight,
                           int maxStrength, Window window, boolean stopFactor, Set<String> countries,
                           Set<String> cities, Map<String, Number> params, String template, List<Action> actions) {}

    public record Action(String key, Kind when, int dueDays) {}

    public record GenreProfile(List<String> subGenres, ProfileField<List<Integer>> audienceAge,
                               ProfileField<List<String>> communities, ProfileField<List<Integer>> typicalPriceEur,
                               ProfileField<Integer> typicalStartHour, ProfileField<Integer> buyingLeadDays) {}

    /** A profile value carrying exactly one provenance marker: a source URL, or {@code estimate}. */
    public record ProfileField<T>(T value, String sourcedUrl, boolean estimate) {}

    /** Stamp for {@code prediction_ledger.question_bank_version} (VARCHAR(32)). */
    public String version() {
        return "qb" + bankVersion + "-gp" + profilesVersion;
    }

    public List<Question> questionsFor(String country) {
        return questions.stream().filter(q -> q.countries().contains(country)).toList();
    }

    /** Every i18n key the webapp must translate: question text per kind, short label, and actions. */
    public SortedSet<String> templateKeys() {
        Map<String, Set<Kind>> kindsById = new LinkedHashMap<>();
        SortedSet<String> keys = new TreeSet<>();
        for (Question q : questions) {
            kindsById.computeIfAbsent(q.template(), t -> new TreeSet<>()).addAll(q.kinds());
            q.actions().forEach(a -> keys.add(a.key()));
        }
        kindsById.forEach((template, kinds) -> {
            if (kinds.size() == 1) {
                keys.add(template);
            } else {
                keys.add(template + ".risk");
                keys.add(template + ".opportunity");
            }
            keys.add("predictor.qShort." + template.substring("predictor.q.".length()));
        });
        return keys;
    }
}
