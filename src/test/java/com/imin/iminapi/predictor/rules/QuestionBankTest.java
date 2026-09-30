package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.predictor.rules.QuestionBank.GenreProfile;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.ProfileField;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

class QuestionBankTest {

    private static final Path TEMPLATE_KEYS = Path.of("src/test/resources/predictor/template-keys.txt");

    private static final String VALID_BANK = """
            version: 2
            thresholds: { adjust_min_risk: 3, move_min_risk: 7, min_coverage: 0.6, max_points_per_finding: 4 }
            questions:
              - id: "4.2"
                family: holidays
                star: true
                source: structured
                kinds: [risk, opportunity]
                weight: 2
                window: night
                stop_factor: false
                applies_when: { countries: [FR, NL] }
                template: predictor.q.4_2
                actions:
                  - { key: predictor.a.eve_theme, when: opportunity, due: "D-21" }
              - id: "2.1"
                family: competition
                source: internal
                kinds: [risk]
                weight: 3
                window: night
                applies_when: { countries: [FR] }
                template: predictor.q.2_1
            """;

    private static final String ORGANIZER_2_1 = """
              - id: "2.1"
                family: competition
                source: organizer
                kinds: [risk]
                weight: 3
                max_strength: 2
                window: night
                applies_when: { countries: [FR] }
                template: predictor.q.2_1
            """;

    // --- programme tests ---

    @Test
    void loadsShippedBank() {
        QuestionBank bank = QuestionBankLoader.load(new DefaultResourceLoader());

        assertThat(bank.questions()).hasSize(19);
        assertThat(bank.questions().stream().map(Question::id).distinct()).hasSize(17);
        assertThat(bank.version()).isEqualTo("qb2-gp1");
        assertThat(bank.profiles()).hasSize(8);
        for (GenreProfile p : bank.profiles().values()) {
            for (ProfileField<?> f : List.of(p.audienceAge(), p.communities(), p.typicalPriceEur(),
                    p.typicalStartHour(), p.buyingLeadDays())) {
                assertThat(f.sourcedUrl() != null ^ f.estimate()).isTrue();
            }
        }
    }

    @Test
    void rejectsWebStopFactor() {
        String bank = VALID_BANK.replace("source: internal",
                "source: web\n    max_strength: 2\n    stop_factor: true");
        assertRejected(bank, "questions[1].stop_factor");
    }

    @Test
    void rejectsDoubleSource() {
        assertRejected(VALID_BANK.replace("source: internal", "source: [internal, organizer]"),
                "questions[1].source: has more than one source");
    }

    @Test
    void versionIsStampedFromYaml() {
        QuestionBank bank = parse(VALID_BANK.replace("version: 2", "version: 7"),
                validProfiles().replace("version: 1", "version: 3"));
        assertThat(bank.version()).isEqualTo("qb7-gp3");
    }

    @Test
    void templateKeysFileIsCurrent() throws IOException {
        List<String> expected = new ArrayList<>(QuestionBankLoader.load(new DefaultResourceLoader()).templateKeys());
        if (Boolean.getBoolean("predictor.writeTemplateKeys")) {
            Files.createDirectories(TEMPLATE_KEYS.getParent());
            Files.write(TEMPLATE_KEYS, expected, StandardCharsets.UTF_8);
            return;
        }
        List<String> actual = Files.exists(TEMPLATE_KEYS) ? Files.readAllLines(TEMPLATE_KEYS) : List.of();
        if (!actual.equals(expected)) {
            fail(TEMPLATE_KEYS + " is stale; rerun ./mvnw test -Dtest=QuestionBankTest -Dpredictor.writeTemplateKeys=true");
        }
    }

    // --- branch tests ---

    @Test
    void rejectsOrganizerStopFactor() {
        String bank = VALID_BANK.replace("source: internal",
                "source: organizer\n    max_strength: 2\n    stop_factor: true");
        assertRejected(bank, "questions[1].stop_factor");
    }

    @Test
    void acceptsInternalStopFactor() {
        QuestionBank bank = parse(VALID_BANK.replace("source: internal", "source: internal\n    stop_factor: true"),
                validProfiles());
        assertThat(bank.questions().get(1).stopFactor()).isTrue();
    }

    @Test
    void rejectsMissingSource() {
        assertRejected(VALID_BANK.replace("    source: internal\n", ""), "questions[1].source: has no source");
    }

    @Test
    void rejectsEmptySourceList() {
        assertRejected(VALID_BANK.replace("source: internal", "source: []"), "questions[1].source: has no source");
    }

    @Test
    void rejectsUnknownSource() {
        assertRejected(VALID_BANK.replace("source: internal", "source: gossip"), "questions[1].source");
    }

    @Test
    void rejectsDuplicateIdAndSource() {
        assertRejected(VALID_BANK + ORGANIZER_2_1.replace("source: organizer", "source: internal"),
                "questions[2].id: duplicate '2.1' for source internal");
    }

    @Test
    void allowsSameIdWithDifferentSources() {
        QuestionBank bank = parse(VALID_BANK + ORGANIZER_2_1, validProfiles());
        assertThat(bank.questions()).extracting(Question::source)
                .containsExactly(SourceKind.STRUCTURED, SourceKind.INTERNAL, SourceKind.ORGANIZER);
        assertThat(bank.questions().get(2).maxStrength()).isEqualTo(2);
    }

    @Test
    void rejectsBadIdPattern() {
        assertRejected(VALID_BANK.replace("id: \"2.1\"", "id: \"2\""), "questions[1].id");
    }

    @Test
    void rejectsEmptyKinds() {
        assertRejected(VALID_BANK.replace("kinds: [risk]", "kinds: []"), "questions[1].kinds");
    }

    @Test
    void rejectsWeightZero() {
        assertRejected(VALID_BANK.replace("weight: 3", "weight: 0"), "questions[1].weight");
    }

    @Test
    void rejectsWeightFour() {
        assertRejected(VALID_BANK.replace("weight: 3", "weight: 4"), "questions[1].weight");
    }

    @Test
    void maxStrengthDefaultsToThree() {
        assertThat(parse(VALID_BANK, validProfiles()).questions().get(1).maxStrength()).isEqualTo(3);
    }

    @Test
    void rejectsOrganizerStrengthThree() {
        assertRejected(VALID_BANK + ORGANIZER_2_1.replace("max_strength: 2", "max_strength: 3"),
                "questions[2].max_strength");
    }

    @Test
    void rejectsInputStrengthThree() {
        assertRejected(VALID_BANK + ORGANIZER_2_1.replace("source: organizer", "source: input")
                        .replace("max_strength: 2", "max_strength: 3"),
                "questions[2].max_strength: must be at most 2 for a web, organizer or input source");
    }

    @Test
    void rejectsMaxStrengthZero() {
        assertRejected(VALID_BANK.replace("source: internal", "source: internal\n    max_strength: 0"),
                "questions[1].max_strength: must be between 1 and 3");
    }

    @Test
    void rejectsMaxStrengthFour() {
        assertRejected(VALID_BANK.replace("source: internal", "source: internal\n    max_strength: 4"),
                "questions[1].max_strength: must be between 1 and 3");
    }

    @Test
    void rejectsUnknownQuestionKey() {
        assertRejected(VALID_BANK.replace("source: internal", "source: internal\n    wieght: 2"),
                "questions[1]: unknown key 'wieght'");
    }

    @Test
    void rejectsUnknownAppliesWhenKey() {
        assertRejected(VALID_BANK.replace("countries: [FR] }", "countries: [FR], citys: [Lille] }"),
                "questions[1].applies_when: unknown key 'citys'");
    }

    @Test
    void rejectsUnknownActionKey() {
        assertRejected(VALID_BANK.replace("due: \"D-21\" }", "due: \"D-21\", dew: \"D-7\" }"),
                "questions[0].actions[0]: unknown key 'dew'");
    }

    @Test
    void rejectsUnknownThresholdsKey() {
        assertRejected(VALID_BANK.replace("max_points_per_finding: 4 }", "max_points_per_finding: 4, min_risk: 1 }"),
                "thresholds: unknown key 'min_risk'");
    }

    @Test
    void rejectsUnknownTopLevelBankKey() {
        assertRejected("threshold_overrides: {}\n" + VALID_BANK, "unknown key 'threshold_overrides'");
    }

    @Test
    void rejectsUnknownTopLevelProfilesKey() {
        assertProfilesRejected("extra: 1\n" + validProfiles(), "unknown key 'extra'");
    }

    @Test
    void rejectsEstimateFalseWithoutSourced() {
        String profiles = validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }",
                "typical_start_hour: { value: 23, estimate: false }");
        assertProfilesRejected(profiles, "typical_start_hour: needs sourced or estimate");
    }

    @Test
    void rejectsBlankCity() {
        assertRejected(VALID_BANK.replace("countries: [FR] }", "countries: [FR], cities: [\" \"] }"),
                "questions[1].applies_when.cities: must not contain a blank name");
    }

    @Test
    void rejectsUnknownWindow() {
        assertRejected(VALID_BANK.replace("window: night\n    applies_when: { countries: [FR] }",
                "window: year\n    applies_when: { countries: [FR] }"), "questions[1].window");
    }

    @Test
    void rejectsEmptyCountries() {
        assertRejected(VALID_BANK.replace("countries: [FR] }", "countries: [] }"),
                "questions[1].applies_when.countries");
    }

    @Test
    void rejectsLowercaseCountry() {
        assertRejected(VALID_BANK.replace("countries: [FR] }", "countries: [fr] }"),
                "questions[1].applies_when.countries");
    }

    @Test
    void rejectsEmptyCities() {
        assertRejected(VALID_BANK.replace("countries: [FR] }", "countries: [FR], cities: [] }"),
                "questions[1].applies_when.cities");
    }

    @Test
    void rejectsMissingAppliesWhen() {
        assertRejected(VALID_BANK.replace("    applies_when: { countries: [FR] }\n", ""),
                "questions[1].applies_when: is missing");
    }

    @Test
    void rejectsNonNumericParam() {
        assertRejected(VALID_BANK.replace("template: predictor.q.2_1", "template: predictor.q.2_1\n    params: { a: x }"),
                "questions[1].params");
    }

    @Test
    void rejectsDueWithoutDPrefix() {
        assertRejected(VALID_BANK.replace("due: \"D-21\"", "due: \"28\""), "questions[0].actions[0].due");
    }

    @Test
    void rejectsActionWhenOutsideKinds() {
        assertRejected(VALID_BANK.replace("kinds: [risk, opportunity]", "kinds: [risk]"),
                "questions[0].actions[0].when");
    }

    @Test
    void rejectsBadActionKey() {
        assertRejected(VALID_BANK.replace("predictor.a.eve_theme", "predictor.a.Eve"), "questions[0].actions[0].key");
    }

    @Test
    void parsesActionDueDays() {
        QuestionBank.Action action = parse(VALID_BANK, validProfiles()).questions().get(0).actions().get(0);
        assertThat(action).isEqualTo(new QuestionBank.Action("predictor.a.eve_theme", Kind.OPPORTUNITY, 21));
    }

    @Test
    void rejectsMissingThresholds() {
        assertRejected(VALID_BANK.replace(
                "thresholds: { adjust_min_risk: 3, move_min_risk: 7, min_coverage: 0.6, max_points_per_finding: 4 }\n",
                ""), "thresholds: is missing");
    }

    @Test
    void rejectsMissingThresholdKey() {
        assertRejected(VALID_BANK.replace(", max_points_per_finding: 4", ""),
                "thresholds.max_points_per_finding: is missing");
    }

    @Test
    void rejectsAdjustNotBelowMove() {
        assertRejected(VALID_BANK.replace("adjust_min_risk: 3", "adjust_min_risk: 7"), "thresholds.adjust_min_risk");
    }

    @Test
    void rejectsCoverageAboveOne() {
        assertRejected(VALID_BANK.replace("min_coverage: 0.6", "min_coverage: 1.5"), "thresholds.min_coverage");
    }

    @Test
    void rejectsMaxPointsTen() {
        assertRejected(VALID_BANK.replace("max_points_per_finding: 4", "max_points_per_finding: 10"),
                "thresholds.max_points_per_finding");
    }

    @Test
    void rejectsZeroVersion() {
        assertRejected(VALID_BANK.replace("version: 2", "version: 0"), "question-bank version");
    }

    @Test
    void rejectsEmptyQuestions() {
        String bank = VALID_BANK.substring(0, VALID_BANK.indexOf("questions:")) + "questions: []\n";
        assertRejected(bank, "questions: must not be empty");
    }

    @Test
    void rejectsTemplateNotMatchingId() {
        assertRejected(VALID_BANK.replace("template: predictor.q.2_1", "template: predictor.q.2_2"),
                "questions[1].template");
    }

    @Test
    void rejectsDuplicateYamlKey() {
        assertRejected(VALID_BANK.replace("weight: 3", "weight: 3\n    weight: 2"), "question-bank: invalid YAML");
    }

    @Test
    void twoKindQuestionExportsRiskAndOpportunityKeys() {
        Set<String> keys = parse(VALID_BANK, validProfiles()).templateKeys();
        assertThat(keys).containsExactly(
                "predictor.a.eve_theme",
                "predictor.q.2_1",
                "predictor.q.4_2.opportunity",
                "predictor.q.4_2.risk",
                "predictor.qShort.2_1",
                "predictor.qShort.4_2");
    }

    @Test
    void questionsForFiltersByCountry() {
        QuestionBank bank = parse(VALID_BANK, validProfiles());
        assertThat(bank.questionsFor("NL")).extracting(Question::id).containsExactly("4.2");
        assertThat(bank.questionsFor("FR")).extracting(Question::id).containsExactly("4.2", "2.1");
    }

    @Test
    void rejectsProfileFieldWithBothMarkers() {
        String profiles = validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }",
                "typical_start_hour: { value: 23, estimate: true, sourced: \"https://example.org\" }");
        assertProfilesRejected(profiles, "typical_start_hour: has both sourced and estimate");
    }

    @Test
    void rejectsProfileFieldWithNoMarker() {
        String profiles = validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }",
                "typical_start_hour: { value: 23 }");
        assertProfilesRejected(profiles, "typical_start_hour: needs sourced or estimate");
    }

    @Test
    void acceptsSourcedProfileField() {
        String profiles = validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }",
                "typical_start_hour: { value: 23, sourced: \"https://example.org\" }");
        ProfileField<Integer> hour = parse(VALID_BANK, profiles).profiles().get("house & techno").typicalStartHour();
        assertThat(hour).isEqualTo(new ProfileField<>(23, "https://example.org", false));
    }

    @Test
    void rejectsSourcedThatIsNotUrl() {
        String profiles = validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }",
                "typical_start_hour: { value: 23, sourced: \"example.org\" }");
        assertProfilesRejected(profiles, "typical_start_hour.sourced: must be a URL");
    }

    @Test
    void rejectsUnknownProfileKey() {
        assertProfilesRejected(validProfiles().replaceFirst("    sub_genres:", "    sub_genre: [x]\n    sub_genres:"),
                "buckets.house & techno: unknown key 'sub_genre'");
    }

    @Test
    void rejectsUnknownProfileFieldKey() {
        String profiles = validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }",
                "typical_start_hour: { value: 23, estimate: true, note: x }");
        assertProfilesRejected(profiles, "typical_start_hour: unknown key 'note'");
    }

    @Test
    void parsesAgeRangeAsImmutableList() {
        ProfileField<List<Integer>> age = parse(VALID_BANK, validProfiles()).profiles().get("house & techno").audienceAge();
        assertThat(age).isEqualTo(new ProfileField<>(List.of(21, 35), null, true));
        assertThatThrownBy(() -> age.value().set(0, 99)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAgeLowEqualsHigh() {
        assertProfilesRejected(validProfiles().replaceFirst("value: \\[21, 35]", "value: [21, 21]"),
                "audience_age.value");
    }

    @Test
    void rejectsAgeHighAbove80() {
        assertProfilesRejected(validProfiles().replaceFirst("value: \\[21, 35]", "value: [21, 81]"),
                "audience_age.value");
    }

    @Test
    void rejectsBlankSubGenre() {
        assertProfilesRejected(validProfiles().replace("[sub pop]", "[\" \"]"),
                "sub_genres: ' ' must be lowercase and not blank");
    }

    @Test
    void rejectsMissingBucket() {
        String profiles = validProfiles();
        int start = profiles.indexOf("  \"pop\":");
        int end = profiles.indexOf("  \"jazz & acoustic\":");
        assertProfilesRejected(profiles.substring(0, start) + profiles.substring(end),
                "buckets: missing bucket 'pop'");
    }

    @Test
    void rejectsUnknownBucket() {
        assertProfilesRejected(validProfiles().replace("\"pop\":", "\"polka\":"), "buckets: unknown bucket 'polka'");
    }

    @Test
    void rejectsAgeOutOfRange() {
        assertProfilesRejected(validProfiles().replaceFirst("value: \\[21, 35]", "value: [15, 35]"), "audience_age");
    }

    @Test
    void rejectsPriceLowAboveHigh() {
        assertProfilesRejected(validProfiles().replaceFirst("value: \\[12, 25]", "value: [30, 25]"),
                "typical_price_eur");
    }

    @Test
    void rejectsStartHour24() {
        assertProfilesRejected(validProfiles().replaceFirst("value: 23,", "value: 24,"), "typical_start_hour");
    }

    @Test
    void rejectsLeadDaysAbove120() {
        assertProfilesRejected(validProfiles().replaceFirst("value: 7,", "value: 121,"), "buying_lead_days");
    }

    @Test
    void rejectsBadCommunityCode() {
        assertProfilesRejected(validProfiles().replaceFirst("value: \\[]", "value: [ngr]"), "communities");
    }

    @Test
    void rejectsSubGenreSharedAcrossBuckets() {
        assertProfilesRejected(validProfiles().replace("[sub pop]", "[sub house & techno]"),
                "sub_genres: 'sub house & techno' already belongs to 'house & techno'");
    }

    @Test
    void rejectsUppercaseSubGenre() {
        assertProfilesRejected(validProfiles().replace("[sub pop]", "[Pop]"), "sub_genres");
    }

    @Test
    void bucketsMatchAudiencePlanWhitelist() throws IOException {
        AudiencePlanLogic logic;
        try (InputStream l = resource("audienceplan/logic-v1.yaml");
             InputStream p = resource("audienceplan/priors-v1.yaml");
             InputStream g = resource("audienceplan/genres-v1.yaml")) {
            logic = LogicLoader.parse(l, p, g);
        }
        assertThat(QuestionBank.GENRE_BUCKETS).containsExactlyElementsOf(logic.genres().whitelist());
        assertThat(new HashSet<>(QuestionBankLoader.load(new DefaultResourceLoader()).profiles().keySet()))
                .isEqualTo(new HashSet<>(logic.genres().whitelist()));
    }

    // --- helpers ---

    private static String validProfiles() {
        StringBuilder sb = new StringBuilder("version: 1\nbuckets:\n");
        for (String bucket : QuestionBank.GENRE_BUCKETS) {
            sb.append("  \"").append(bucket).append("\":\n")
                    .append("    sub_genres: [sub ").append(bucket).append("]\n")
                    .append("    audience_age: { value: [21, 35], estimate: true }\n")
                    .append("    communities: { value: [], estimate: true }\n")
                    .append("    typical_price_eur: { value: [12, 25], estimate: true }\n")
                    .append("    typical_start_hour: { value: 23, estimate: true }\n")
                    .append("    buying_lead_days: { value: 7, estimate: true }\n");
        }
        return sb.toString();
    }

    private static QuestionBank parse(String bank, String profiles) {
        return QuestionBankLoader.parse(stream(bank), stream(profiles));
    }

    private static void assertRejected(String bank, String message) {
        assertThatThrownBy(() -> parse(bank, validProfiles()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predictor question-bank")
                .hasMessageContaining(message);
    }

    private static void assertProfilesRejected(String profiles, String message) {
        assertThatThrownBy(() -> parse(VALID_BANK, profiles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predictor genre-profiles")
                .hasMessageContaining(message);
    }

    private static InputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream resource(String path) {
        InputStream in = QuestionBankTest.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new UncheckedIOException(new IOException("missing " + path));
        }
        return in;
    }
}
