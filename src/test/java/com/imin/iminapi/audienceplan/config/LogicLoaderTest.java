package com.imin.iminapi.audienceplan.config;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.BandPoint;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Exclusion;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.TimingArm;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.ClassRule;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.ProofRequirement;
import com.imin.iminapi.util.EventNormalization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class LogicLoaderTest {

    private static final String LOGIC = read("audienceplan/logic-v1.yaml");
    private static final String PRIORS = read("audienceplan/priors-v1.yaml");
    private static final String GENRES = read("audienceplan/genres-v1.yaml");
    private static final String DERIVED_LISTENERS =
            "source: \"CNM\", derived: true, note: \"computed from CNM, confirm at M0-5\" }";

    // ---- shipped files ----

    @Test
    void shippedFiles_loadWithVersionOne() {
        AudiencePlanLogic l = shipped();
        assertThat(l.logicVersion()).isEqualTo(1);
        assertThat(l.priorsVersion()).isEqualTo(1);
        assertThat(l.genres().version()).isEqualTo(1);
    }

    @Test
    void shippedLogic_equalsSpec() {
        AudiencePlanLogic.Logic l = shipped().logic();
        assertThat(l.modes()).isEqualTo(new AudiencePlanLogic.Modes(50, 500));
        assertThat(l.targetDefaultPct()).isEqualTo(85);
        assertThat(l.minSegmentToShow()).isEqualTo(10);
        assertThat(l.tasteHalfLifeDays()).isEqualTo(180);
        assertThat(l.inviteOtherGenreOnlyIfCoverageBelow()).isEqualTo(0.15);
        assertThat(l.coverageVerdict()).isEqualTo(new AudiencePlanLogic.CoverageVerdict(BandPoint.MID, 0.30, 0.15));
        assertThat(l.experiments()).isEqualTo(new AudiencePlanLogic.Experiments(15, 60, List.of(TimingArm.LAUNCH, TimingArm.D3)));
        assertThat(l.exclusions()).containsExactly(Exclusion.CONSENT_GATE_FALSE, Exclusion.BOUGHT_THIS_EVENT,
                Exclusion.EMAILED_LAST_48H, Exclusion.SENDS_THIS_EVENT_GTE_2, Exclusion.SENDS_30D_GTE_4);
        assertThat(l.classes()).containsExactly(
                new ClassRule("loyal", 3, null, null, 90, null, false),
                new ClassRule("repeat", 2, 2, null, 90, null, false),
                new ClassRule("first_timer", 1, 1, null, 90, null, false),
                new ClassRule("lapsing", null, null, 91, 180, null, false),
                new ClassRule("dormant", null, null, 181, null, 1095, false),
                new ClassRule("imported", null, 0, null, null, null, true));
        AudiencePlanLogic.Legal legal = l.legal();
        assertThat(legal.retentionDays()).isEqualTo(1095);
        assertThat(legal.softOptInRequiresPaidOrderAndOrgSeller()).isTrue();
        assertThat(legal.esRobinsonCheck()).isFalse();
        assertThat(legal.explicitSources()).isEqualTo(Map.of(
                "checkout", ProofRequirement.ORGANIZER_NAMED_TEXT_VERSION,
                "organizer_import_row", ProofRequirement.PROVENANCE_ROW,
                "door_qr", ProofRequirement.TEXT_VERSION,
                "survey", ProofRequirement.TEXT_VERSION));
    }

    static Stream<Arguments> textVersions() {
        return Stream.of(
                arguments("checkout", (Function<AudiencePlanLogic.Legal, Collection<String>>)
                        AudiencePlanLogic.Legal::organizerNamedTextVersions, "checkout-org-named-2026-09"),
                arguments("door", (Function<AudiencePlanLogic.Legal, Collection<String>>)
                        AudiencePlanLogic.Legal::doorQrTextVersions, "door-org-named-2026-09"),
                arguments("survey", (Function<AudiencePlanLogic.Legal, Collection<String>>)
                        AudiencePlanLogic.Legal::surveyTextVersions, "survey-org-named-2026-09"),
                arguments("surveyNotice", (Function<AudiencePlanLogic.Legal, Collection<String>>)
                        AudiencePlanLogic.Legal::surveyNoticeVersions, "survey-notice-2026-10"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textVersions")
    void textVersions_holdExactlyTheShippedPageVersion(String surface,
                                                       Function<AudiencePlanLogic.Legal, Collection<String>> versions,
                                                       String expected) {
        assertThat(versions.apply(shipped().logic().legal())).containsExactly(expected);
    }

    @Test
    void instagramOrganicUnknown_loadsEmptyNotZero() {
        assertThat(shipped().priors().instagramOrganic()).isEqualTo(Optional.empty());
    }

    @Test
    void instagramOrganicKnown_loadsBand() {
        AudiencePlanLogic l = parse(LOGIC, PRIORS.replace("instagram_organic: unknown",
                "instagram_organic: [0.01, 0.02, 0.03]"), GENRES);
        assertThat(l.priors().instagramOrganic()).contains(new Band(0.01, 0.02, 0.03));
    }

    @Test
    void whitelist_isExactlyTheEightEventGenreKeys() {
        List<String> labels = List.of("House & Techno", "Bass & Hard Dance", "Club / Open Format", "Hip-Hop & R&B",
                "Latin & Afrobeats", "Rock & Alternative", "Pop", "Jazz & Acoustic");
        assertThat(shipped().genres().whitelist())
                .containsExactlyElementsOf(labels.stream().map(EventNormalization::genreKey).toList());
    }

    @Test
    void adjacency_isSymmetric() {
        Map<String, Set<String>> adj = shipped().genres().adjacency();
        assertThat(adj.get("club / open format")).containsExactlyInAnyOrder(
                "house & techno", "bass & hard dance", "pop");
        assertThat(adj.get("house & techno")).containsExactly("club / open format");
        assertThat(adj.get("latin & afrobeats")).containsExactly("hip-hop & r&b");
        assertThat(adj.get("jazz & acoustic")).containsExactly("rock & alternative");
    }

    @Test
    void springBean_loadsFromConfiguredFiles() {
        new ApplicationContextRunner()
                .withUserConfiguration(AudiencePlanConfig.class)
                .run(ctx -> {
                    AudiencePlanProperties props = ctx.getBean(AudiencePlanProperties.class);
                    assertThat(props.getLogicFile()).isEqualTo("classpath:audienceplan/logic-v1.yaml");
                    assertThat(props.getPriorsFile()).isEqualTo("classpath:audienceplan/priors-v1.yaml");
                    assertThat(props.getGenresFile()).isEqualTo("classpath:audienceplan/genres-v1.yaml");
                    assertThat(ctx.getBean(AudiencePlanLogic.class).logicVersion()).isEqualTo(1);
                });
    }

    @Test
    void springBean_missingFile_failsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(AudiencePlanConfig.class)
                .withPropertyValues("imin.audience-plan.logic-file=classpath:audienceplan/nope.yaml")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                            "audience plan: file not found: classpath:audienceplan/nope.yaml");
                });
    }


    // ---- cross-file and structure ----

    @Test
    void duplicateYamlKey_fails() {
        assertThatThrownBy(() -> parse(LOGIC, PRIORS.replace("prior_strength_invitations: 20",
                "prior_strength_invitations: 20\nclasses:\n  loyal: { purchase_rate: [0.1, 0.2, 0.3], "
                        + "unsub_per_send: [0.001, 0.002, 0.004] }"), GENRES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("audience plan priors: invalid YAML:")
                .hasMessageContaining("duplicate key classes");
    }

    @Test
    void duplicateKeyUnderPriorsClasses_fails() {
        assertThatThrownBy(() -> parse(LOGIC, PRIORS.replace("  repeat:      {",
                "  loyal:       { purchase_rate: [0.1, 0.2, 0.3], unsub_per_send: [0.001, 0.002, 0.004] }\n"
                        + "  repeat:      {"), GENRES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("audience plan priors: invalid YAML:")
                .hasMessageContaining("duplicate key loyal");
    }

    @Test
    void rootNotAMapping_fails() {
        assertThatThrownBy(() -> parse("- 1", PRIORS, GENRES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audience plan logic: expected a mapping at the root");
    }

    @ParameterizedTest
    @ValueSource(strings = {"logic", "priors", "genres"})
    void nonPositiveVersion_fails(String file) {
        String logic = file.equals("logic") ? LOGIC.replace("version: 1", "version: 0") : LOGIC;
        String priors = file.equals("priors") ? PRIORS.replace("version: 1", "version: 0") : PRIORS;
        String genres = file.equals("genres") ? GENRES.replace("version: 1", "version: 0") : GENRES;
        assertThatThrownBy(() -> parse(logic, priors, genres))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audience plan " + file + ".version: must be a positive integer");
    }



    // ---- rejected edits: one row per rule, file x edit x message ----

    static Stream<Arguments> logicRejections() {
        return Stream.of(
                arguments("zeroCatchmentRadius_fails",
                        (UnaryOperator<String>) l -> l.replace("radius_km: 70", "radius_km: 0"),
                        "logic.catchment.radius_km: must be a positive integer"),
                arguments("missingCatchment_fails",
                        (UnaryOperator<String>) l -> l.replace("catchment: { radius_km: 70 }\n", ""),
                        "logic.catchment: is missing"),
                arguments("missingKey_failsNamingIt",
                        (UnaryOperator<String>) l -> l.replace("target_default_pct: 85\n", ""),
                        "logic.target_default_pct: is missing"),
                arguments("integerGivenAsDecimal_fails",
                        (UnaryOperator<String>) l -> l.replace("target_default_pct: 85", "target_default_pct: 85.5"),
                        "logic.target_default_pct: expected an integer"),
                arguments("booleanGivenAsText_fails",
                        (UnaryOperator<String>) l -> l.replace("es_robinson_check: false", "es_robinson_check: maybe"),
                        "logic.legal.es_robinson_check: expected true or false"),
                arguments("classEntryNotAMapping_fails",
                        (UnaryOperator<String>) l -> l.replace("  - { key: imported,    paid_orders_max: 0, requires_import_basis: true }", "  - imported"),
                        "logic.classes: entry 5 must be a mapping"),
                arguments("listGivenAsScalar_fails",
                        (UnaryOperator<String>) l -> l.replace("exclusions: [consent_gate_false, bought_this_event, emailed_last_48h, " + "sends_this_event_gte_2, sends_30d_gte_4]", "exclusions: none"),
                        "logic.exclusions: expected a list"),
                arguments("mappingGivenAsScalar_fails",
                        (UnaryOperator<String>) l -> l.replace("taste: { half_life_days: 180 }", "taste: 180"),
                        "logic.taste: expected a mapping"),
                arguments("unknownProofRequirement_fails",
                        (UnaryOperator<String>) l -> l.replace("survey: text_version", "survey: organizer_said_so"),
                        "logic.legal.explicit_sources.survey: unknown value 'organizer_said_so'"),
                arguments("unknownVerdictPoint_fails",
                        (UnaryOperator<String>) l -> l.replace("verdict_on: mid", "verdict_on: median"),
                        "logic.coverage_verdict.verdict_on: unknown value 'median'"),
                arguments("unknownExclusion_fails",
                        (UnaryOperator<String>) l -> l.replace("sends_30d_gte_4]", "sends_30d_gte_5]"),
                        "logic.exclusions: unknown value 'sends_30d_gte_5'"),
                arguments("unknownTimingArm_fails",
                        (UnaryOperator<String>) l -> l.replace("default_timing_arms: [launch, d3]", "default_timing_arms: [launch, d7]"),
                        "logic.experiments.default_timing_arms: unknown value 'd7'"),
                arguments("paidOrdersMinAboveMax_fails",
                        (UnaryOperator<String>) l -> l.replace("paid_orders_min: 2, paid_orders_max: 2", "paid_orders_min: 3, paid_orders_max: 2"),
                        "logic.classes[1].paid_orders_min: must not exceed paid_orders_max"),
                arguments("daysSinceLastPaidMinAboveMax_fails",
                        (UnaryOperator<String>) l -> l.replace("days_since_last_paid_min: 91, days_since_last_paid_max: 180", "days_since_last_paid_min: 181, days_since_last_paid_max: 180"),
                        "logic.classes[3].days_since_last_paid_min: must not exceed days_since_last_paid_max"),
                arguments("duplicateClassKey_fails",
                        (UnaryOperator<String>) l -> l.replace("{ key: repeat,", "{ key: loyal,"),
                        "logic.classes[1].key: duplicate class 'loyal'"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("logicRejections")
    void logicFile_rejectsABrokenEdit(String name, UnaryOperator<String> edit, String message) {
        assertLogicFail(edit, message);
    }

    static Stream<Arguments> priorsRejections() {
        return Stream.of(
                arguments("lowAboveMid_fails",
                        (UnaryOperator<String>) p -> p.replace("[0.12, 0.25, 0.40]", "[0.30, 0.25, 0.40]"),
                        "priors.classes.loyal.purchase_rate: must satisfy low <= mid <= high"),
                arguments("midAboveHigh_fails",
                        (UnaryOperator<String>) p -> p.replace("[0.12, 0.25, 0.40]", "[0.12, 0.45, 0.40]"),
                        "priors.classes.loyal.purchase_rate: must satisfy low <= mid <= high"),
                arguments("valueAboveOne_fails",
                        (UnaryOperator<String>) p -> p.replace("[0.12, 0.25, 0.40]", "[0.12, 0.25, 1.40]"),
                        "priors.classes.loyal.purchase_rate: must be between 0 and 1"),
                arguments("negativeRate_fails",
                        (UnaryOperator<String>) p -> p.replace("[0.12, 0.25, 0.40]", "[-0.12, 0.25, 0.40]"),
                        "priors.classes.loyal.purchase_rate: must be between 0 and 1"),
                arguments("bandNotATriple_fails",
                        (UnaryOperator<String>) p -> p.replace("[0.12, 0.25, 0.40]", "[0.12, 0.40]"),
                        "priors.classes.loyal.purchase_rate: expected [low, mid, high]"),
                arguments("unknownWhereABandIsRequired_fails",
                        (UnaryOperator<String>) p -> p.replace("[0.12, 0.25, 0.40]", "unknown"),
                        "priors.classes.loyal.purchase_rate: expected [low, mid, high]"),
                arguments("fractionAboveOne_fails",
                        (UnaryOperator<String>) p -> p.replace("same: 1.0", "same: 1.5"),
                        "priors.modifiers.genre_fit.same: must be between 0 and 1"),
                arguments("unknownGenreFitOfZero_fails",
                        (UnaryOperator<String>) p -> p.replace("unknown: 1.0", "unknown: 0"),
                        "priors.modifiers.genre_fit.unknown: must be greater than 0"),
                arguments("missingUnknownGenreFit_fails",
                        (UnaryOperator<String>) p -> p.replace(", unknown: 1.0", ""),
                        "priors.modifiers.genre_fit.unknown: is missing"),
                arguments("numberGivenAsText_fails",
                        (UnaryOperator<String>) p -> p.replace("ctr: 0.027", "ctr: high"),
                        "priors.reach_to_ticket.meta_ads.ctr: expected a number"),
                arguments("classWithoutPrior_fails",
                        (UnaryOperator<String>) p -> p.replaceAll("(?m)^  imported: .*\\n", ""),
                        "audience plan priors: class 'imported' has no prior"),
                arguments("nonStringScalar_fails",
                        (UnaryOperator<String>) p -> p.replace("note: \"US arts and entertainment, unverified\"", "note: 5"),
                        "priors.reach_to_ticket.meta_ads.note: expected text"),
                arguments("tribeRateLowAboveHigh_fails",
                        (UnaryOperator<String>) p -> p.replace("low: 0.41, high: 0.44", "low: 0.45, high: 0.44"),
                        "priors.tribe_size.bar_club_concert_goers.low: must not exceed high"),
                arguments("tribeRateBlankSource_fails",
                        (UnaryOperator<String>) p -> p.replace("source: \"Ekhoscènes\"", "source: \" \""),
                        "priors.tribe_size.electronic_first.source: must not be blank"),
                arguments("tribeRateSourcedWithoutYear_fails",
                        (UnaryOperator<String>) p -> p.replace("source: \"Ekhoscènes\", year: 2024", "source: \"Ekhoscènes\""),
                        "priors.tribe_size.electronic_first.year: is missing"),
                arguments("tribeRateDerivedWithYear_fails",
                        (UnaryOperator<String>) p -> p.replaceFirst(Pattern.quote(DERIVED_LISTENERS), "source: \"CNM\", derived: true, year: 2023, note: \"x\" }"),
                        "priors.tribe_size.music_listeners.year: must be absent on a derived rate"),
                arguments("tribeRateDerivedWithoutNote_fails",
                        (UnaryOperator<String>) p -> p.replaceFirst(Pattern.quote(DERIVED_LISTENERS), "source: \"CNM\", derived: true }"),
                        "priors.tribe_size.music_listeners.note: is missing"),
                arguments("tribeRateDerivedBlankNote_fails",
                        (UnaryOperator<String>) p -> p.replaceFirst(Pattern.quote(DERIVED_LISTENERS), "source: \"CNM\", derived: true, note: \" \" }"),
                        "priors.tribe_size.music_listeners.note: must not be blank"),
                arguments("tribeRateNonPositiveYear_fails",
                        (UnaryOperator<String>) p -> p.replace("source: \"Ekhoscènes\", year: 2024", "source: \"Ekhoscènes\", year: 0"),
                        "priors.tribe_size.electronic_first.year: must be positive"),
                arguments("tribeShareGenreNotWhitelisted_fails",
                        (UnaryOperator<String>) p -> p.replace("\"bass & hard dance\"]", "\"techno\"]"),
                        "priors.tribe_size.electronic_first.genres: 'techno' is not a whitelisted genre"),
                arguments("tribeShareGenreOnTwoRates_fails",
                        (UnaryOperator<String>) p -> p.replace("year: 2023 }", "year: 2023, genres: [\"house & techno\"] }"),
                        "priors.tribe_size.electronic_first.genres: 'house & techno' already has a share on bar_club_concert_goers"),
                arguments("tribeShareGenresNotAList_fails",
                        (UnaryOperator<String>) p -> p.replace("year: 2023 }", "year: 2023, genres: \"pop\" }"),
                        "priors.tribe_size.bar_club_concert_goers.genres: expected a list"),
                arguments("ticketsPerOrderOfZero",
                        (UnaryOperator<String>) p -> p.replace("tickets_per_order: [1.3, 1.6, 2.2]", "tickets_per_order: [0, 1.6, 2.2]"),
                        "priors.tickets_per_order: must be positive"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("priorsRejections")
    void priorsFile_rejectsABrokenEdit(String name, UnaryOperator<String> edit, String message) {
        assertPriorsFail(edit, message);
    }

    static Stream<Arguments> genresRejections() {
        return Stream.of(
                arguments("nullListEntry_fails",
                        (UnaryOperator<String>) g -> g.replace("  - \"pop\"\n", "  - \"pop\"\n  - ~\n"),
                        "genres.whitelist: entry 7 must be text"),
                arguments("adjacencyEntryNotText_fails",
                        (UnaryOperator<String>) g -> g.replace("[\"pop\", \"club / open format\"]", "[\"pop\", 5]"),
                        "genres.adjacency: each entry must be a pair of genre keys"),
                arguments("adjacencyToNonWhitelistedKey_fails",
                        (UnaryOperator<String>) g -> g.replace("[\"pop\", \"club / open format\"]", "[\"pop\", \"disco\"]"),
                        "genres.adjacency: 'disco' is not a whitelisted genre"),
                arguments("adjacencyEntryNotAPair_fails",
                        (UnaryOperator<String>) g -> g.replace("[\"pop\", \"club / open format\"]", "[\"pop\"]"),
                        "genres.adjacency: each entry must be a pair of genre keys"),
                arguments("whitelistedKeyWithForbiddenTerm_fails",
                        (UnaryOperator<String>) g -> g.replace("  - \"pop\"\n", "  - \"pop\"\n  - \"Queer Night\"\n"),
                        "genres.whitelist: 'Queer Night' contains forbidden term 'queer'"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("genresRejections")
    void genresFile_rejectsABrokenEdit(String name, UnaryOperator<String> edit, String message) {
        assertGenresFail(edit, message);
    }

    // ---- helpers ----

    private static AudiencePlanLogic shipped() {
        return parse(LOGIC, PRIORS, GENRES);
    }

    private static void assertLogicFail(UnaryOperator<String> edit, String message) {
        String edited = edit.apply(LOGIC);
        assertThat(edited).isNotEqualTo(LOGIC);
        assertFails(edited, PRIORS, GENRES, message);
    }

    private static void assertPriorsFail(UnaryOperator<String> edit, String message) {
        String edited = edit.apply(PRIORS);
        assertThat(edited).isNotEqualTo(PRIORS);
        assertFails(LOGIC, edited, GENRES, message);
    }

    private static void assertGenresFail(UnaryOperator<String> edit, String message) {
        String edited = edit.apply(GENRES);
        assertThat(edited).isNotEqualTo(GENRES);
        assertFails(LOGIC, PRIORS, edited, message);
    }

    private static void assertFails(String logic, String priors, String genres, String message) {
        assertThatThrownBy(() -> parse(logic, priors, genres))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith(message);
    }

    private static AudiencePlanLogic parse(String logic, String priors, String genres) {
        return LogicLoader.parse(stream(logic), stream(priors), stream(genres));
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(String path) {
        try (InputStream in = LogicLoaderTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
