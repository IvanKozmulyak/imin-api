package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.service.RadarAlertRule.Signal;
import com.imin.iminapi.predictor.service.RadarAlertRule.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RadarAlertRuleTest {

    private static Signal structuredRisk(String q) {
        return new Signal(q, "risk", "found", "structured", null);
    }

    private static Signal webRisk(String q, String url) {
        return new Signal(q, "risk", "found", "web", url);
    }

    private static Snapshot snap(String verdict, Signal... findings) {
        return new Snapshot(verdict, 0, List.of(findings));
    }

    static Stream<Arguments> verdictPairs() {
        List<String> v = List.of("good", "adjust", "move");
        return v.stream().flatMap(from -> v.stream().map(to -> Arguments.of(from, to, v.indexOf(to) > v.indexOf(from))));
    }

    @ParameterizedTest(name = "{0} -> {1} alerts={2}")
    @MethodSource("verdictPairs")
    void alertsOnlyWhenVerdictWorsens(String from, String to, boolean alerts) {
        assertThat(RadarAlertRule.shouldAlert(snap(from), snap(to, structuredRisk("4.1")))).isEqualTo(alerts);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"good,not_enough_data", "not_enough_data,move", "not_enough_data,not_enough_data"})
    void notEnoughDataNeverAlerts(String from, String to) {
        assertThat(RadarAlertRule.shouldAlert(snap(from), snap(to, structuredRisk("4.1")))).isFalse();
    }

    @Test
    void unknownVerdictNeverAlerts() {
        assertThat(RadarAlertRule.shouldAlert(snap(null), snap("move", structuredRisk("4.1")))).isFalse();
        assertThat(RadarAlertRule.shouldAlert(snap("good"), snap(null, structuredRisk("4.1")))).isFalse();
        assertThat(RadarAlertRule.shouldAlert(snap("fine"), snap("move", structuredRisk("4.1")))).isFalse();
        assertThat(RadarAlertRule.shouldAlert(snap("good"), snap("terrible", structuredRisk("4.1")))).isFalse();
    }

    @Test
    void structuredRiskAlertsImmediately() {
        assertThat(RadarAlertRule.shouldAlert(snap("good"), snap("adjust", structuredRisk("4.3")))).isTrue();
    }

    @Test
    void internalRiskAlertsImmediately() {
        Signal internal = new Signal("7.1", "risk", "found", "internal", null);
        assertThat(RadarAlertRule.shouldAlert(snap("good"), snap("adjust", internal))).isTrue();
    }

    @Test
    void riskAlreadyFoundDoesNotQualify() {
        assertThat(RadarAlertRule.shouldAlert(snap("good", structuredRisk("4.1")), snap("move", structuredRisk("4.1"))))
                .isFalse();
    }

    @Test
    void questionFoundFromAnotherSourceDoesNotQualify() {
        Snapshot base = snap("good", webRisk("4.1", "https://example.com/a"));
        assertThat(RadarAlertRule.shouldAlert(base, snap("move", structuredRisk("4.1")))).isFalse();
    }

    @Test
    void newOpportunityDoesNotQualify() {
        Signal opp = new Signal("4.2", "opportunity", "found", "structured", null);
        assertThat(RadarAlertRule.shouldAlert(snap("good"), snap("move", opp))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"clear", "not_checked"})
    void unfoundRowDoesNotQualify(String status) {
        Signal s = new Signal("4.1", "risk", status, "structured", null);
        assertThat(RadarAlertRule.shouldAlert(snap("good"), snap("move", s))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"organizer", "input"})
    void softSourceDoesNotQualify(String source) {
        Signal s = new Signal("6.1", "risk", "found", source, null);
        assertThat(RadarAlertRule.shouldAlert(snap("good"), snap("move", s))).isFalse();
    }

    @Test
    void worseVerdictWithoutQualifyingFindingDoesNotAlert() {
        assertThat(RadarAlertRule.shouldAlert(snap("good"), snap("move"))).isFalse();
    }

    @Test
    void singleWebSignalDoesNotAlert() {
        assertThat(RadarAlertRule.shouldAlert(snap("good"), snap("move", webRisk("2.1", "https://example.com/a"))))
                .isFalse();
    }

    @Test
    void repeatedWebSignalAlerts() {
        Snapshot base = snap("good", webRisk("2.1", "https://www.Example.com/a/"));
        assertThat(RadarAlertRule.shouldAlert(base, snap("move", webRisk("2.1", "http://example.com/a#x")))).isTrue();
    }

    @Test
    void webSignalMustMatchQuestionAndUrl() {
        Snapshot base = snap("good", webRisk("2.1", "https://example.com/a"));
        assertThat(RadarAlertRule.shouldAlert(base, snap("move", webRisk("2.1", "https://example.com/b")))).isFalse();
        assertThat(RadarAlertRule.shouldAlert(base, snap("move", webRisk("2.2", "https://example.com/a")))).isFalse();
    }

    @Test
    void webSignalWithoutUrlNeverQualifies() {
        assertThat(RadarAlertRule.shouldAlert(snap("good", webRisk("2.1", null)), snap("move", webRisk("2.1", null))))
                .isFalse();
        assertThat(RadarAlertRule.shouldAlert(snap("good", webRisk("2.1", "::nope")),
                snap("move", webRisk("2.1", "::nope")))).isFalse();
    }

    @Test
    void missingBaselineNeverAlerts() {
        assertThat(RadarAlertRule.shouldAlert(null, snap("move", structuredRisk("4.1")))).isFalse();
        assertThat(RadarAlertRule.shouldAlert(snap("good"), null)).isFalse();
    }

    @ParameterizedTest(name = "[{0}] -> [{1}]")
    @CsvSource(nullValues = "NULL", value = {
            "https://www.Example.COM/a/, example.com/a",
            "http://example.com/a#frag, example.com/a",
            "HTTPS://example.com, example.com",
            "https://example.com:8443/a?b=1&c=2, example.com:8443/a?b=1&c=2",
            "'  https://example.com/x/  ', example.com/x",
            "/relative/path, NULL",
            "ftp://example.com/a, NULL",
            "mailto:a@example.com, NULL",
            "'   ', NULL",
            "NULL, NULL",
            "http://exa mple.com, NULL"})
    void normaliseUrl(String raw, String expected) {
        assertThat(RadarAlertRule.normaliseUrl(raw)).isEqualTo(expected);
    }

    @Test
    void alertKindsMatchSchemaCheck() throws Exception {
        Path v168 = Files.list(Path.of("src/main/resources/db/migration"))
                .filter(p -> p.getFileName().toString().endsWith("__predictor_alert.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(v168, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("ck_predictor_alert_kind CHECK \\(kind IN \\(([^)]*)\\)\\)").matcher(sql);
        assertThat(m.find()).isTrue();
        Set<String> allowed = Stream.of(m.group(1).split(","))
                .map(s -> s.trim().replace("'", "")).collect(Collectors.toSet());
        assertThat(Set.of(PredictorAlertStore.KIND_BAND, PredictorAlertStore.KIND_RADAR)).isEqualTo(allowed);
    }
}
