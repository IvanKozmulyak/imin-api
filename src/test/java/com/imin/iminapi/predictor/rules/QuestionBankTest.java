package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.predictor.rules.QuestionBank.GenreProfile;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.ProfileField;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.rules.QuestionBank.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

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

        assertThat(bank.questions()).hasSize(26);
        assertThat(bank.questions().stream().map(Question::id).distinct()).hasSize(21);
        assertThat(bank.version()).isEqualTo("qb5-gp1");
        assertThat(bank.profiles()).hasSize(8);
        for (GenreProfile p : bank.profiles().values()) {
            for (ProfileField<?> f : List.of(p.audienceAge(), p.communities(), p.typicalPriceEur(),
                    p.typicalStartHour(), p.buyingLeadDays())) {
                assertThat(f.sourcedUrl() != null ^ f.estimate()).isTrue();
            }
        }
    }

    @Test
    void openDataQuestionsAreNotStar() {
        QuestionBank bank = QuestionBankLoader.load(new DefaultResourceLoader());

        for (String id : List.of("2.6", "5.3", "2.3")) {
            assertThat(bank.questions()).filteredOn(q -> q.id().equals(id) && q.source() == SourceKind.STRUCTURED)
                    .singleElement().satisfies(q -> {
                assertThat(q.star()).as(id).isFalse();
                assertThat(q.source()).isEqualTo(SourceKind.STRUCTURED);
                assertThat(q.kinds()).containsExactly(Kind.RISK);
                assertThat(q.countries()).containsExactlyInAnyOrder("FR", "NL", "DE", "ES", "UA");
            });
        }
    }

    @Test
    void webQuestionsAreCappedRiskRowsWithoutStopFactor() {
        QuestionBank bank = QuestionBankLoader.load(new DefaultResourceLoader());

        List<Question> web = bank.questions().stream().filter(q -> q.source() == SourceKind.WEB).toList();
        assertThat(web).extracting(Question::id).containsExactly("2.1", "2.2", "5.3");
        assertThat(web).extracting(Question::window).containsExactly(Window.NIGHT, Window.WEEK, Window.NIGHT);
        assertThat(web).extracting(Question::weight).containsExactly(3, 2, 2);
        for (Question q : web) {
            assertThat(q.maxStrength()).as(q.id()).isEqualTo(2);
            assertThat(q.stopFactor()).as(q.id()).isFalse();
            assertThat(q.kinds()).as(q.id()).containsExactly(Kind.RISK);
            assertThat(q.countries()).containsExactlyInAnyOrder("FR", "NL", "DE", "ES", "UA");
        }
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
    void acceptsInternalStopFactor() {
        QuestionBank bank = parse(VALID_BANK.replace("source: internal", "source: internal\n    stop_factor: true"),
                validProfiles());
        assertThat(bank.questions().get(1).stopFactor()).isTrue();
    }

    @Test
    void allowsSameIdWithDifferentSources() {
        QuestionBank bank = parse(VALID_BANK + ORGANIZER_2_1, validProfiles());
        assertThat(bank.questions()).extracting(Question::source)
                .containsExactly(SourceKind.STRUCTURED, SourceKind.INTERNAL, SourceKind.ORGANIZER);
        assertThat(bank.questions().get(2).maxStrength()).isEqualTo(2);
    }

    @Test
    void maxStrengthDefaultsToThree() {
        assertThat(parse(VALID_BANK, validProfiles()).questions().get(1).maxStrength()).isEqualTo(3);
    }

    @Test
    void parsesActionDueDays() {
        QuestionBank.Action action = parse(VALID_BANK, validProfiles()).questions().get(0).actions().get(0);
        assertThat(action).isEqualTo(new QuestionBank.Action("predictor.a.eve_theme", Kind.OPPORTUNITY, 21));
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
    void acceptsSourcedProfileField() {
        String profiles = validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }",
                "typical_start_hour: { value: 23, sourced: \"https://example.org\" }");
        ProfileField<Integer> hour = parse(VALID_BANK, profiles).profiles().get("house & techno").typicalStartHour();
        assertThat(hour).isEqualTo(new ProfileField<>(23, "https://example.org", false));
    }

    @Test
    void parsesAgeRangeAsImmutableList() {
        ProfileField<List<Integer>> age = parse(VALID_BANK, validProfiles()).profiles().get("house & techno").audienceAge();
        assertThat(age).isEqualTo(new ProfileField<>(List.of(21, 35), null, true));
        assertThatThrownBy(() -> age.value().set(0, 99)).isInstanceOf(UnsupportedOperationException.class);
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

    // --- rejected files: the edit and the message fragment it must name ---

    static Stream<Arguments> rejectedBanks() {
        return Stream.of(
                Arguments.of("WebStopFactor",
                        VALID_BANK.replace("source: internal", "source: web\n    max_strength: 2\n    stop_factor: true"),
                        "questions[1].stop_factor"),
                Arguments.of("DoubleSource",
                        VALID_BANK.replace("source: internal", "source: [internal, organizer]"),
                        "questions[1].source: has more than one source"),
                Arguments.of("OrganizerStopFactor",
                        VALID_BANK.replace("source: internal", "source: organizer\n    max_strength: 2\n    stop_factor: true"),
                        "questions[1].stop_factor"),
                Arguments.of("MissingSource",
                        VALID_BANK.replace("    source: internal\n", ""),
                        "questions[1].source: has no source"),
                Arguments.of("EmptySourceList",
                        VALID_BANK.replace("source: internal", "source: []"),
                        "questions[1].source: has no source"),
                Arguments.of("UnknownSource",
                        VALID_BANK.replace("source: internal", "source: gossip"),
                        "questions[1].source"),
                Arguments.of("DuplicateIdAndSource",
                        VALID_BANK + ORGANIZER_2_1.replace("source: organizer", "source: internal"),
                        "questions[2].id: duplicate '2.1' for source internal"),
                Arguments.of("BadIdPattern",
                        VALID_BANK.replace("id: \"2.1\"", "id: \"2\""),
                        "questions[1].id"),
                Arguments.of("EmptyKinds",
                        VALID_BANK.replace("kinds: [risk]", "kinds: []"),
                        "questions[1].kinds"),
                Arguments.of("WeightZero",
                        VALID_BANK.replace("weight: 3", "weight: 0"),
                        "questions[1].weight"),
                Arguments.of("WeightFour",
                        VALID_BANK.replace("weight: 3", "weight: 4"),
                        "questions[1].weight"),
                Arguments.of("OrganizerStrengthThree",
                        VALID_BANK + ORGANIZER_2_1.replace("max_strength: 2", "max_strength: 3"),
                        "questions[2].max_strength"),
                Arguments.of("InputStrengthThree",
                        VALID_BANK + ORGANIZER_2_1.replace("source: organizer", "source: input") .replace("max_strength: 2", "max_strength: 3"),
                        "questions[2].max_strength: must be at most 2 for a web, organizer or input source"),
                Arguments.of("MaxStrengthZero",
                        VALID_BANK.replace("source: internal", "source: internal\n    max_strength: 0"),
                        "questions[1].max_strength: must be between 1 and 3"),
                Arguments.of("MaxStrengthFour",
                        VALID_BANK.replace("source: internal", "source: internal\n    max_strength: 4"),
                        "questions[1].max_strength: must be between 1 and 3"),
                Arguments.of("UnknownQuestionKey",
                        VALID_BANK.replace("source: internal", "source: internal\n    wieght: 2"),
                        "questions[1]: unknown key 'wieght'"),
                Arguments.of("UnknownAppliesWhenKey",
                        VALID_BANK.replace("countries: [FR] }", "countries: [FR], citys: [Lille] }"),
                        "questions[1].applies_when: unknown key 'citys'"),
                Arguments.of("UnknownActionKey",
                        VALID_BANK.replace("due: \"D-21\" }", "due: \"D-21\", dew: \"D-7\" }"),
                        "questions[0].actions[0]: unknown key 'dew'"),
                Arguments.of("UnknownThresholdsKey",
                        VALID_BANK.replace("max_points_per_finding: 4 }", "max_points_per_finding: 4, min_risk: 1 }"),
                        "thresholds: unknown key 'min_risk'"),
                Arguments.of("UnknownTopLevelBankKey",
                        "threshold_overrides: {}\n" + VALID_BANK,
                        "unknown key 'threshold_overrides'"),
                Arguments.of("BlankCity",
                        VALID_BANK.replace("countries: [FR] }", "countries: [FR], cities: [\" \"] }"),
                        "questions[1].applies_when.cities: must not contain a blank name"),
                Arguments.of("UnknownWindow",
                        VALID_BANK.replace("window: night\n    applies_when: { countries: [FR] }", "window: year\n    applies_when: { countries: [FR] }"),
                        "questions[1].window"),
                Arguments.of("EmptyCountries",
                        VALID_BANK.replace("countries: [FR] }", "countries: [] }"),
                        "questions[1].applies_when.countries"),
                Arguments.of("LowercaseCountry",
                        VALID_BANK.replace("countries: [FR] }", "countries: [fr] }"),
                        "questions[1].applies_when.countries"),
                Arguments.of("EmptyCities",
                        VALID_BANK.replace("countries: [FR] }", "countries: [FR], cities: [] }"),
                        "questions[1].applies_when.cities"),
                Arguments.of("MissingAppliesWhen",
                        VALID_BANK.replace("    applies_when: { countries: [FR] }\n", ""),
                        "questions[1].applies_when: is missing"),
                Arguments.of("NonNumericParam",
                        VALID_BANK.replace("template: predictor.q.2_1", "template: predictor.q.2_1\n    params: { a: x }"),
                        "questions[1].params"),
                Arguments.of("DueWithoutDPrefix",
                        VALID_BANK.replace("due: \"D-21\"", "due: \"28\""),
                        "questions[0].actions[0].due"),
                Arguments.of("ActionWhenOutsideKinds",
                        VALID_BANK.replace("kinds: [risk, opportunity]", "kinds: [risk]"),
                        "questions[0].actions[0].when"),
                Arguments.of("BadActionKey",
                        VALID_BANK.replace("predictor.a.eve_theme", "predictor.a.Eve"),
                        "questions[0].actions[0].key"),
                Arguments.of("MissingThresholds",
                        VALID_BANK.replace( "thresholds: { adjust_min_risk: 3, move_min_risk: 7, min_coverage: 0.6, max_points_per_finding: 4 }\n", ""),
                        "thresholds: is missing"),
                Arguments.of("MissingThresholdKey",
                        VALID_BANK.replace(", max_points_per_finding: 4", ""),
                        "thresholds.max_points_per_finding: is missing"),
                Arguments.of("AdjustNotBelowMove",
                        VALID_BANK.replace("adjust_min_risk: 3", "adjust_min_risk: 7"),
                        "thresholds.adjust_min_risk"),
                Arguments.of("CoverageAboveOne",
                        VALID_BANK.replace("min_coverage: 0.6", "min_coverage: 1.5"),
                        "thresholds.min_coverage"),
                Arguments.of("MaxPointsTen",
                        VALID_BANK.replace("max_points_per_finding: 4", "max_points_per_finding: 10"),
                        "thresholds.max_points_per_finding"),
                Arguments.of("ZeroVersion",
                        VALID_BANK.replace("version: 2", "version: 0"),
                        "question-bank version"),
                Arguments.of("EmptyQuestions",
                        emptyQuestionsBank(),
                        "questions: must not be empty"),
                Arguments.of("TemplateNotMatchingId",
                        VALID_BANK.replace("template: predictor.q.2_1", "template: predictor.q.2_2"),
                        "questions[1].template"),
                Arguments.of("DuplicateYamlKey",
                        VALID_BANK.replace("weight: 3", "weight: 3\n    weight: 2"),
                        "question-bank: invalid YAML"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedBanks")
    void rejectsBank(String name, String bank, String message) {
        assertRejected(bank, message);
    }

    static Stream<Arguments> rejectedProfiles() {
        return Stream.of(
                Arguments.of("UnknownTopLevelProfilesKey",
                        "extra: 1\n" + validProfiles(),
                        "unknown key 'extra'"),
                Arguments.of("EstimateFalseWithoutSourced",
                        validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }", "typical_start_hour: { value: 23, estimate: false }"),
                        "typical_start_hour: needs sourced or estimate"),
                Arguments.of("ProfileFieldWithBothMarkers",
                        validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }", "typical_start_hour: { value: 23, estimate: true, sourced: \"https://example.org\" }"),
                        "typical_start_hour: has both sourced and estimate"),
                Arguments.of("ProfileFieldWithNoMarker",
                        validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }", "typical_start_hour: { value: 23 }"),
                        "typical_start_hour: needs sourced or estimate"),
                Arguments.of("SourcedThatIsNotUrl",
                        validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }", "typical_start_hour: { value: 23, sourced: \"example.org\" }"),
                        "typical_start_hour.sourced: must be a URL"),
                Arguments.of("UnknownProfileKey",
                        validProfiles().replaceFirst("    sub_genres:", "    sub_genre: [x]\n    sub_genres:"),
                        "buckets.house & techno: unknown key 'sub_genre'"),
                Arguments.of("UnknownProfileFieldKey",
                        validProfiles().replaceFirst("typical_start_hour: \\{ value: 23, estimate: true }", "typical_start_hour: { value: 23, estimate: true, note: x }"),
                        "typical_start_hour: unknown key 'note'"),
                Arguments.of("AgeLowEqualsHigh",
                        validProfiles().replaceFirst("value: \\[21, 35]", "value: [21, 21]"),
                        "audience_age.value"),
                Arguments.of("AgeHighAbove80",
                        validProfiles().replaceFirst("value: \\[21, 35]", "value: [21, 81]"),
                        "audience_age.value"),
                Arguments.of("BlankSubGenre",
                        validProfiles().replace("[sub pop]", "[\" \"]"),
                        "sub_genres: ' ' must be lowercase and not blank"),
                Arguments.of("MissingBucket",
                        withoutPopBucket(),
                        "buckets: missing bucket 'pop'"),
                Arguments.of("UnknownBucket",
                        validProfiles().replace("\"pop\":", "\"polka\":"),
                        "buckets: unknown bucket 'polka'"),
                Arguments.of("AgeOutOfRange",
                        validProfiles().replaceFirst("value: \\[21, 35]", "value: [15, 35]"),
                        "audience_age"),
                Arguments.of("PriceLowAboveHigh",
                        validProfiles().replaceFirst("value: \\[12, 25]", "value: [30, 25]"),
                        "typical_price_eur"),
                Arguments.of("StartHour24",
                        validProfiles().replaceFirst("value: 23,", "value: 24,"),
                        "typical_start_hour"),
                Arguments.of("LeadDaysAbove120",
                        validProfiles().replaceFirst("value: 7,", "value: 121,"),
                        "buying_lead_days"),
                Arguments.of("BadCommunityCode",
                        validProfiles().replaceFirst("value: \\[]", "value: [ngr]"),
                        "communities"),
                Arguments.of("SubGenreSharedAcrossBuckets",
                        validProfiles().replace("[sub pop]", "[sub house & techno]"),
                        "sub_genres: 'sub house & techno' already belongs to 'house & techno'"),
                Arguments.of("UppercaseSubGenre",
                        validProfiles().replace("[sub pop]", "[Pop]"),
                        "sub_genres"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedProfiles")
    void rejectsProfiles(String name, String profiles, String message) {
        assertProfilesRejected(profiles, message);
    }

    private static String emptyQuestionsBank() {
        return VALID_BANK.substring(0, VALID_BANK.indexOf("questions:")) + "questions: []\n";
    }

    private static String withoutPopBucket() {
        String profiles = validProfiles();
        int start = profiles.indexOf("  \"pop\":");
        int end = profiles.indexOf("  \"jazz & acoustic\":");
        return profiles.substring(0, start) + profiles.substring(end);
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
