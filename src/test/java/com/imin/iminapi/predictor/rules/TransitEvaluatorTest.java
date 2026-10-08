package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.model.TransitDisruption;
import com.imin.iminapi.predictor.model.TransitSyncState;
import com.imin.iminapi.predictor.repository.TransitDisruptionRepository;
import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.prim.PrimProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.imin.iminapi.predictor.rules.RuleFixtures.BANK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransitEvaluatorTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    private static final LocalDate D = TODAY.plusDays(2);
    private static final Instant FEED_UPDATED = Instant.parse("2026-10-07T11:58:17Z");
    private static final String URL = "https://prim.iledefrance-mobilites.fr/fr/apis/idfm-disruptions_bulk";
    private static final String LICENCE_URL = "https://prim.iledefrance-mobilites.fr/fr/licences";

    private final TransitDisruptionRepository disruptions = mock(TransitDisruptionRepository.class);
    private final TransitSyncStateRepository states = mock(TransitSyncStateRepository.class);
    private final SourceGates gates = mock(SourceGates.class);
    private final List<TransitDisruption> rows = new ArrayList<>();
    private TransitEvaluator evaluator;

    @BeforeEach
    void setUp() {
        when(gates.keys()).thenReturn(Set.of("date-check", "weather", "wikimedia", "football", "openagenda",
                "quefaireaparis", "prim"));
        when(gates.isOn("prim")).thenReturn(true);
        evaluator = new TransitEvaluator(BANK, disruptions, states, gates,
                DataSourceCatalog.load(new DefaultResourceLoader(), gates), new PrimProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        state(NOW.minus(Duration.ofMinutes(10)), FEED_UPDATED);
        when(disruptions.findOverlapping(anyString(), any(), any())).thenReturn(rows);
    }

    private void state(Instant syncedAt, Instant feedUpdatedAt) {
        TransitSyncState s = new TransitSyncState();
        s.setSource("idfm-prim");
        s.setSyncedAt(syncedAt);
        s.setFeedUpdatedAt(feedUpdatedAt);
        s.setLastStatus("ok");
        s.setLastAttemptAt(NOW);
        when(states.findById("idfm-prim")).thenReturn(Optional.of(s));
    }

    private static Question q(String id) {
        return RuleFixtures.q(id, SourceKind.STRUCTURED);
    }

    private static DateCheckInput in(Integer startHour, Integer endHour) {
        return new DateCheckInput("Paris", "FR", "75011", null, null, "house & techno", null, 300, 2000L, "club",
                startHour, endHour, List.of(), null, UUID.randomUUID(), TODAY, null, null, null, null);
    }

    private Finding ask(String id, LocalDate date) {
        return evaluator.evaluate(q(id), in(null, null), date);
    }

    /** Paris wall-clock "yyyy-MM-ddTHH:mm" pairs. */
    private static String periods(String... beginEnd) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < beginEnd.length; i += 2) {
            out.add("{\"begin\":\"" + paris(beginEnd[i]) + "\",\"end\":\"" + paris(beginEnd[i + 1]) + "\"}");
        }
        return "[" + String.join(",", out) + "]";
    }

    private static Instant paris(String local) {
        return LocalDateTime.parse(local).atZone(PARIS).toInstant();
    }

    /** {@code label|mode|level} entries. */
    private static String lines(String... objects) {
        return Stream.of(objects).map(o -> {
            String[] p = o.split("\\|");
            return "{\"ref\":\"line:IDFM:" + p[0] + "\",\"label\":\"" + p[0] + "\",\"mode\":\"" + p[1]
                    + "\",\"level\":\"" + p[2] + "\"}";
        }).collect(Collectors.joining(",", "[", "]"));
    }

    private void row(String id, String kind, String severity, String linesJson, String periodsJson) {
        TransitDisruption d = new TransitDisruption();
        d.setSource("idfm-prim");
        d.setDisruptionId(id);
        d.setKind(kind);
        d.setSeverity(severity);
        d.setLinesJson(linesJson);
        d.setPeriodsJson(periodsJson);
        rows.add(d);
    }

    private static String night(LocalDate d) {
        return d + "T23:00";
    }

    private static String nightEnd(LocalDate d) {
        return d.plusDays(1) + "T01:00";
    }

    @Test
    void gateOffIsSourceOff() {
        when(gates.isOn("prim")).thenReturn(false);

        assertThat(ask("6.1", D).facts()).containsEntry("reason", "source_off");
        assertThat(ask("6.1", D).status()).isEqualTo(Status.NOT_CHECKED);
    }

    @Test
    void noStateIsNotSynced() {
        when(states.findById("idfm-prim")).thenReturn(Optional.empty());
        assertThat(ask("6.2", D).facts()).containsEntry("reason", "not_synced");

        state(null, null);
        assertThat(ask("6.2", D).facts()).containsEntry("reason", "not_synced");
    }

    @Test
    void oldSyncIsStale() {
        state(NOW.minus(Duration.ofHours(6)).minusSeconds(1), FEED_UPDATED);
        assertThat(ask("6.1", D).facts()).containsEntry("reason", "stale");

        state(NOW.minus(Duration.ofHours(6)), FEED_UPDATED);
        assertThat(ask("6.1", D).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void frozenFeedIsStaleEvenAfterAFreshPoll() {
        // the poll succeeded a minute ago, but the feed's own update time has not moved for over 6 h
        state(NOW.minusSeconds(60), NOW.minus(Duration.ofHours(6)).minusSeconds(1));
        assertThat(ask("6.2", D).facts()).containsEntry("reason", "stale");

        state(NOW.minusSeconds(60), NOW.minus(Duration.ofHours(6)));
        assertThat(ask("6.2", D).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void okStateWithZeroRowsIsClear() {
        state(NOW.minusSeconds(60), null);

        Finding f = ask("6.1", D);

        assertThat(f.status()).isEqualTo(Status.CLEAR);
        assertThat(f.url()).isEqualTo(URL);
        // no feed update time: the poll time dates the data
        assertThat(f.fetchedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(f.facts()).containsEntry("scope", "network").containsEntry("licence", "Licence Mobilités")
                .containsEntry("licenceUrl", LICENCE_URL).containsKey("credit").doesNotContainKeys("date", "lines");
    }

    @Test
    void strikeFound() {
        row("s1", "strike", "PERTURBEE", lines("RER B|RapidTransit|line"), periods(D + "T05:00", D + "T23:59"));

        Finding f = ask("6.1", D);

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(1);
        assertThat(f.weight()).isEqualTo(2);
        assertThat(f.stopFactor()).isFalse();
        assertThat(f.url()).isEqualTo(URL);
        assertThat(f.fetchedAt()).isEqualTo(FEED_UPDATED);
        assertThat(f.facts()).containsEntry("date", D.toString()).containsEntry("lines", "RER B")
                .containsEntry("lineCount", 1).containsEntry("severity", "PERTURBEE").containsEntry("scope", "network")
                .containsEntry("licence", "Licence Mobilités").containsEntry("licenceUrl", LICENCE_URL)
                .containsEntry("credit", "Contient des informations de Messages Info Trafic – requête globale, "
                        + "mises à disposition aux conditions de la « Licence Mobilités »");
    }

    static Stream<Arguments> strikeStrength() {
        return Stream.of(
                Arguments.of("PERTURBEE", List.of("M1|Metro|line", "M4|Metro|line", "T3a|Tramway|line"), 2),
                Arguments.of("BLOQUANTE", List.of("T14|Tramway|line"), 2),
                Arguments.of("PERTURBEE", List.of("T14|Tramway|line"), 1));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource
    void strikeStrength(String severity, List<String> objects, int strength) {
        row("s", "strike", severity, lines(objects.toArray(String[]::new)), periods(night(D), nightEnd(D)));

        assertThat(ask("6.1", D).strength()).isEqualTo(strength);
    }

    static Stream<Arguments> worksStrength() {
        return Stream.of(
                Arguments.of("BLOQUANTE", List.of("RER A|RapidTransit|line", "RER B|RapidTransit|line",
                        "Transilien H|LocalTrain|line"), 2),
                Arguments.of("BLOQUANTE", List.of("RER A|RapidTransit|line"), 1),
                Arguments.of("PERTURBEE", List.of("RER A|RapidTransit|line"), 0),
                Arguments.of("BLOQUANTE", List.of("M1|Metro|stop"), 0),
                Arguments.of("BLOQUANTE", List.of("Bus 211|Bus|line"), 0));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource
    void worksStrength(String severity, List<String> objects, int strength) {
        row("w", "works", severity, lines(objects.toArray(String[]::new)), periods(night(D), nightEnd(D)));

        Finding f = ask("6.2", D);

        assertThat(f.status()).isEqualTo(strength == 0 ? Status.CLEAR : Status.FOUND);
        assertThat(f.strength()).isEqualTo(strength);
        if (strength > 0) assertThat(f.weight()).isEqualTo(1);
    }

    @Test
    void periodOutsideWindowIsClear() {
        row("before", "works", "BLOQUANTE", lines("RER A|RapidTransit|line"), periods(D + "T18:00", D + "T21:59"));
        row("after", "works", "BLOQUANTE", lines("RER B|RapidTransit|line"),
                periods(D.plusDays(1) + "T02:01", D.plusDays(1) + "T05:00"));

        assertThat(ask("6.2", D).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void organizerHoursNarrowTheWindow() {
        // 23:30-01:00 is inside the default 22-02 night, outside an organizer's 20-23 night (same-day end)
        row("late", "works", "BLOQUANTE", lines("RER A|RapidTransit|line"), periods(D + "T23:30", nightEnd(D)));
        Question q = q("6.2");

        assertThat(evaluator.evaluate(q, in(null, null), D).status()).isEqualTo(Status.FOUND);
        assertThat(evaluator.evaluate(q, in(20, 23), D).status()).isEqualTo(Status.CLEAR);

        row("early", "works", "BLOQUANTE", lines("RER C|RapidTransit|line"), periods(D + "T20:30", D + "T21:00"));
        assertThat(evaluator.evaluate(q, in(20, 23), D).facts()).containsEntry("lines", "RER C");
    }

    @Test
    void windowInVenueZoneNotUtc() {
        // Paris 22:30-23:00 is 20:30Z-21:00Z: a window computed in UTC (22:00Z-02:00Z) would miss it
        row("w", "works", "BLOQUANTE", lines("RER D|RapidTransit|line"), periods(D + "T22:30", D + "T23:00"));

        assertThat(ask("6.2", D).status()).isEqualTo(Status.FOUND);
    }

    @Test
    void nothingBeyondHorizonIsTooFarAhead() {
        assertThat(ask("6.1", TODAY.plusDays(3)).facts()).containsEntry("reason", "too_far_ahead");
        assertThat(ask("6.2", TODAY.plusDays(15)).facts()).containsEntry("reason", "too_far_ahead");
        assertThat(ask("6.1", TODAY.plusDays(2)).status()).isEqualTo(Status.CLEAR);
        assertThat(ask("6.2", TODAY.plusDays(14)).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void foundBeyondHorizonStaysFound() {
        LocalDate far = TODAY.plusDays(60);
        row("w", "works", "BLOQUANTE", lines("M13|Metro|line"), periods(night(far), nightEnd(far)));

        assertThat(ask("6.2", far).status()).isEqualTo(Status.FOUND);
    }

    @Test
    void worksNeverAnswerStrikeAndViceVersa() {
        row("w", "works", "BLOQUANTE", lines("RER A|RapidTransit|line"), periods(night(D), nightEnd(D)));
        assertThat(ask("6.1", D).status()).isEqualTo(Status.CLEAR);

        rows.clear();
        row("s", "strike", "BLOQUANTE", lines("RER A|RapidTransit|line"), periods(night(D), nightEnd(D)));
        row("o", "other", "BLOQUANTE", lines("RER B|RapidTransit|line"), periods(night(D), nightEnd(D)));
        assertThat(ask("6.2", D).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void linesCapAtFivePlusN() {
        row("s", "strike", "PERTURBEE", lines("M1|Metro|line", "M2|Metro|line", "M3|Metro|line", "M4|Metro|line",
                "M5|Metro|line", "M6|Metro|line", "M7|Metro|line"), periods(night(D), nightEnd(D)));

        assertThat(ask("6.1", D).facts()).containsEntry("lines", "M1, M2, M3, M4, M5 +2").containsEntry("lineCount", 7);
    }

    @Test
    void malformedRowIsSkippedNotFatal() {
        row("bad", "works", "BLOQUANTE", "{not json", periods(night(D), nightEnd(D)));
        row("good", "works", "BLOQUANTE", lines("RER E|RapidTransit|line"), periods(night(D), nightEnd(D)));

        assertThat(ask("6.2", D).facts()).containsEntry("lines", "RER E");
    }

    @Test
    void bothQuestionsShareOneStateReadAndOneQuery() {
        List<Finding> out = evaluator.evaluateAll(List.of(q("6.1"), q("6.2")), in(null, null), D);

        assertThat(out).extracting(Finding::questionId).containsExactly("6.1", "6.2");
        verify(states, times(1)).findById("idfm-prim");
        verify(disruptions, times(1)).findOverlapping(anyString(), any(), any());
    }
}
