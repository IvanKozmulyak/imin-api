package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.calendar.CalendarSyncProperties;
import com.imin.iminapi.predictor.calendar.FootballDataProperties;
import com.imin.iminapi.predictor.calendar.FootballFixturesSync;
import com.imin.iminapi.predictor.calendar.OpenHolidaysSync;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PublicDataSourcesResponse.PublicDataSource;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsProperties;
import com.imin.iminapi.predictor.sources.prim.PrimProperties;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceCatalogTest {

    private final CalendarSyncProperties calendar = new CalendarSyncProperties();
    private final PredictorProperties predictor = new PredictorProperties();
    private final DateCheckProperties dateCheck = new DateCheckProperties();
    private final WikimediaProperties wikimedia = new WikimediaProperties();
    private final FootballDataProperties football = new FootballDataProperties();
    private final OpenEventsProperties openEvents = new OpenEventsProperties();
    private final PrimProperties prim = new PrimProperties();
    private final SourceGates gates = new SourceGates(calendar, predictor, dateCheck, wikimedia, football, openEvents,
            prim);

    private static final DataSourceCatalog.SyncDates NO_DATES = dates(Map.of(), Map.of(), null, null);

    /** Answers from the two maps and records what was asked; a null list records nothing. */
    private static DataSourceCatalog.SyncDates dates(Map<String, LocalDate> byPrefix, Map<String, LocalDate> bySource,
                                                     List<String> askedPrefixes, List<String> askedSources) {
        return new DataSourceCatalog.SyncDates() {
            @Override
            public Optional<LocalDate> lastUpdated(String prefix) {
                if (askedPrefixes != null) askedPrefixes.add(prefix);
                return Optional.ofNullable(byPrefix.get(prefix));
            }

            @Override
            public Optional<LocalDate> lastUpdatedOfSource(String source) {
                if (askedSources != null) askedSources.add(source);
                return Optional.ofNullable(bySource.get(source));
            }
        };
    }

    private void gates(boolean dateCheckOn, boolean syncOn, boolean weatherOn) {
        gates(dateCheckOn, syncOn, weatherOn, true);
    }

    private void gates(boolean dateCheckOn, boolean syncOn, boolean weatherOn, boolean wikimediaOn) {
        dateCheck.setEnabled(dateCheckOn);
        calendar.setSyncEnabled(syncOn);
        predictor.setWeatherEnabled(weatherOn);
        wikimedia.setEnabled(wikimediaOn);
    }

    private List<String> activeIds() {
        return real().active(NO_DATES).stream().map(PublicDataSource::id).toList();
    }

    private DataSourceCatalog real() {
        return DataSourceCatalog.load(new DefaultResourceLoader(), gates);
    }

    @SuppressWarnings("unchecked")
    private List<String> yamlIds() throws IOException {
        try (InputStream in = new DefaultResourceLoader()
                .getResource(DataSourceCatalog.LOCATION).getInputStream()) {
            Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(in);
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> s : (List<Map<String, Object>>) root.get("sources")) {
                ids.add((String) s.get("id"));
            }
            return ids;
        }
    }

    @Test
    void sourcesListIncludesEveryConfiguredSource() throws IOException {
        gates(true, true, true);
        football(true, "k");
        openEvents(true, "oa_pk_k", true);
        prim.setApiKey("prim-key");

        DataSourceCatalog catalog = real();
        List<PublicDataSource> active = catalog.active(NO_DATES);

        assertThat(catalog.reviewedOn()).isEqualTo("2026-10-07");
        assertThat(active).extracting(PublicDataSource::id).containsExactlyElementsOf(yamlIds());
        assertThat(active).extracting(PublicDataSource::id).containsExactly(
                "calendrier-api-gouv", "fr-en-calendrier-scolaire", "openholidays", "football-data", "iana-tz",
                "openjdk-hijrah", "openweather", "wikimedia-pageviews", "openagenda", "quefaireaparis", "idfm-prim");
        for (PublicDataSource s : active) {
            assertThat(s.status()).isEqualTo("active");
            assertThat(List.of(s.id(), s.name(), s.licence(), s.licenceUrl(), s.creditLine(), s.url()))
                    .allSatisfy(v -> assertThat(v).isNotBlank());
            assertThat(s.usedFor()).isNotEmpty().allSatisfy(v -> assertThat(v).isNotBlank());
            assertThat(s.url()).startsWith("https://");
            assertThat(s.licenceUrl()).startsWith("https://");
            assertThat(s.lastUpdated()).isNull();
        }
    }

    @Test
    void licenceOuverteCreditsNameTheProducer() {
        gates(true, true, false);

        Map<String, String> credits = new HashMap<>();
        real().active(NO_DATES).forEach(s -> credits.put(s.id(), s.creditLine()));

        assertThat(credits.get("calendrier-api-gouv")).contains("DINUM (calendrier.api.gouv.fr)");
        assertThat(credits.get("fr-en-calendrier-scolaire"))
                .contains("Ministère de l'Éducation nationale (data.education.gouv.fr)");
    }

    @Test
    void openHolidaysListedUnderDateCheckWithOdblCredit() {
        gates(true, true, false);

        PublicDataSource oh = real().active(NO_DATES).stream()
                .filter(s -> s.id().equals("openholidays")).findFirst().orElseThrow();

        assertThat(oh.name()).isEqualTo("OpenHolidays API");
        assertThat(oh.usedFor()).containsExactly("public_holidays");
        assertThat(oh.licence()).isEqualTo("ODbL 1.0");
        assertThat(oh.licenceUrl()).isEqualTo("https://opendatacommons.org/licenses/odbl/1-0/");
        assertThat(oh.creditLine()).contains("OpenHolidays API (openholidaysapi.org)").contains("ODbL");
        assertThat(oh.url()).isEqualTo("https://www.openholidaysapi.org/");
        // the prefix must cover the URLs OpenHolidaysSync stores, or lastUpdated stays null
        assertThat(OpenHolidaysSync.url("LU", 2026)).startsWith("https://openholidaysapi.org/");
        gates(false, true, false);
        assertThat(activeIds()).doesNotContain("openholidays");
    }

    @Test
    void wikimediaListedOnlyWhileGateOnWithCc0Credit() {
        gates(true, true, false, true);

        PublicDataSource wm = real().active(NO_DATES).stream()
                .filter(s -> s.id().equals("wikimedia-pageviews")).findFirst().orElseThrow();

        assertThat(wm.name()).isEqualTo("Wikimedia Pageviews");
        assertThat(wm.usedFor()).containsExactly("genre_interest");
        assertThat(wm.licence()).isEqualTo("CC0 1.0");
        assertThat(wm.licenceUrl()).isEqualTo("https://creativecommons.org/publicdomain/zero/1.0/");
        assertThat(wm.creditLine()).contains("Wikimedia Pageviews").contains("Wikimedia Foundation").contains("CC0 1.0");
        assertThat(wm.url()).isEqualTo("https://doc.wikimedia.org/generated-data-platform/aqs/analytics-api/");
        assertThat(wm.lastUpdated()).isNull();

        gates(false, true, false, true);
        assertThat(activeIds()).doesNotContain("wikimedia-pageviews");
        gates(true, true, false, false);
        assertThat(activeIds()).doesNotContain("wikimedia-pageviews").contains("openholidays");
    }

    private void football(boolean enabled, String key) {
        football.setEnabled(enabled);
        football.setApiKey(key);
    }

    @Test
    void footballListedOnlyWhileGateOnWithCredit() {
        gates(true, true, false);
        football(true, "k");

        PublicDataSource fd = real().active(NO_DATES).stream()
                .filter(s -> s.id().equals("football-data")).findFirst().orElseThrow();

        assertThat(fd.name()).isEqualTo("football-data.org");
        assertThat(fd.usedFor()).containsExactly("football_fixtures");
        assertThat(fd.licence()).isEqualTo("football-data.org General Terms and Conditions");
        assertThat(fd.licenceUrl()).isEqualTo("https://www.football-data.org/about");
        assertThat(fd.creditLine()).isEqualTo("Football data provided by the Football-Data.org API");
        assertThat(fd.url()).isEqualTo("https://www.football-data.org/");
        // the prefix must cover the URLs FootballFixturesSync stores, or lastUpdated stays null
        List<String> asked = new ArrayList<>();
        real().active(dates(Map.of(), Map.of(), asked, null));
        assertThat(asked).contains("https://api.football-data.org/v4/competitions/");
        assertThat(FootballFixturesSync.url("FL1")).startsWith("https://api.football-data.org/v4/competitions/");

        football(false, "k");
        assertThat(activeIds()).doesNotContain("football-data").contains("openholidays");
        football(true, " ");
        assertThat(activeIds()).doesNotContain("football-data");
        football(true, "k");
        gates(false, true, false);
        assertThat(activeIds()).doesNotContain("football-data");
        gates(true, false, false);
        assertThat(activeIds()).doesNotContain("football-data");
    }

    private void openEvents(boolean openagenda, String key, boolean quefaireaparis) {
        openEvents.setOpenagendaEnabled(openagenda);
        openEvents.setOpenagendaApiKey(key);
        openEvents.setQuefaireaparisEnabled(quefaireaparis);
    }

    @Test
    void openEventSourcesListedOnlyWhileGateOnWithCredit() {
        gates(true, true, false);
        openEvents(true, "oa_pk_k", true);

        Map<String, PublicDataSource> byId = new HashMap<>();
        real().active(NO_DATES).forEach(s -> byId.put(s.id(), s));

        PublicDataSource oa = byId.get("openagenda");
        assertThat(oa.name()).isEqualTo("OpenAgenda (Ville de Lille, Métropole Européenne de Lille)");
        assertThat(oa.usedFor()).containsExactly("local_events");
        assertThat(oa.licence()).isEqualTo("Licence Ouverte 2.0");
        assertThat(oa.licenceUrl()).isEqualTo("https://www.etalab.gouv.fr/licence-ouverte-open-licence/");
        assertThat(oa.url()).isEqualTo("https://openagenda.com/");
        assertThat(oa.lastUpdated()).isNull();
        PublicDataSource qf = byId.get("quefaireaparis");
        assertThat(qf.name()).isEqualTo("Que Faire à Paris (opendata.paris.fr)");
        assertThat(qf.usedFor()).containsExactly("local_events");
        assertThat(qf.licence()).isEqualTo("ODbL 1.0");
        assertThat(qf.licenceUrl()).isEqualTo("https://opendatacommons.org/licenses/odbl/1-0/");
        assertThat(qf.creditLine())
                .isEqualTo("Que Faire à Paris : Ville de Paris, Direction de la Communication (opendata.paris.fr), ODbL");
        assertThat(qf.url()).isEqualTo("https://opendata.paris.fr/explore/dataset/que-faire-a-paris-/");
        assertThat(qf.lastUpdated()).isNull();

        openEvents(false, "oa_pk_k", true);
        assertThat(activeIds()).doesNotContain("openagenda").contains("quefaireaparis");
        openEvents(true, "", true);
        assertThat(activeIds()).doesNotContain("openagenda");
        openEvents(true, "oa_pk_k", false);
        assertThat(activeIds()).contains("openagenda").doesNotContain("quefaireaparis");
        openEvents(true, "oa_pk_k", true);
        gates(false, true, false);
        assertThat(activeIds()).doesNotContain("openagenda", "quefaireaparis");
    }

    @Test
    void byIdIgnoresGate() {
        gates(false, false, false, false);
        DataSourceCatalog catalog = real();

        assertThat(catalog.active(NO_DATES)).isEmpty();
        assertThat(catalog.byId("openagenda")).hasValueSatisfying(s -> assertThat(s.licence()).isEqualTo("Licence Ouverte 2.0"));
        assertThat(catalog.byId("quefaireaparis")).hasValueSatisfying(s -> assertThat(s.licence()).isEqualTo("ODbL 1.0"));
        assertThat(catalog.byId("datatourisme")).isEmpty();
    }

    @Test
    void openAgendaCreditNamesEveryAgenda() {
        String credit = real().byId("openagenda").orElseThrow().creditLine();

        assertThat(credit).isEqualTo(
                "Agendas : Ville de Lille, Métropole Européenne de Lille (openagenda.com), Licence Ouverte 2.0");
        for (OpenEventCities.City city : OpenEventCities.load(new DefaultResourceLoader()).cities()) {
            city.openagenda().forEach(a -> assertThat(credit).as(a.slug()).contains(a.name()));
        }
    }

    @Test
    void dateCheckOffHidesCalendarSources() {
        gates(false, true, true);

        assertThat(activeIds()).containsExactly("openweather");
    }

    @Test
    void calendarSyncOffHidesCalendarSources() {
        gates(true, false, true);

        // wikimedia does not read the calendar sync, so it stays listed
        assertThat(activeIds()).containsExactly("openweather", "wikimedia-pageviews");
    }

    @Test
    void weatherOffHidesOpenWeather() {
        gates(true, true, false);

        assertThat(activeIds()).containsExactly(
                "calendrier-api-gouv", "fr-en-calendrier-scolaire", "openholidays", "iana-tz", "openjdk-hijrah",
                "wikimedia-pageviews");
    }

    @Test
    void openAgendaGateNeedsDateCheckFlagAndKey() {
        assertThat(gates.keys()).contains("openagenda", "quefaireaparis");
        dateCheck.setEnabled(true);
        openEvents.setOpenagendaEnabled(true);
        assertThat(gates.isOn("openagenda")).as("no key").isFalse();
        openEvents.setOpenagendaApiKey("oa_pk_k");
        assertThat(gates.isOn("openagenda")).isTrue();
        dateCheck.setEnabled(false);
        assertThat(gates.isOn("openagenda")).as("date check off").isFalse();
        dateCheck.setEnabled(true);
        openEvents.setOpenagendaEnabled(false);
        assertThat(gates.isOn("openagenda")).as("flag off").isFalse();
    }

    @Test
    void queFaireGateNeedsDateCheckAndFlag() {
        dateCheck.setEnabled(true);
        assertThat(gates.isOn("quefaireaparis")).as("flag off").isFalse();
        openEvents.setQuefaireaparisEnabled(true);
        assertThat(gates.isOn("quefaireaparis")).isTrue();
        dateCheck.setEnabled(false);
        assertThat(gates.isOn("quefaireaparis")).as("date check off").isFalse();
    }

    @Test
    void allGatesOffReturnsEmpty() {
        gates(false, false, false, false);

        assertThat(real().active(NO_DATES)).isEmpty();
    }

    @Test
    void gatesAreReadAtCallTimeNotAtLoad() {
        gates(true, true, true);
        DataSourceCatalog catalog = real();

        predictor.setWeatherEnabled(false);
        dateCheck.setEnabled(false);

        assertThat(catalog.active(NO_DATES)).isEmpty();
    }

    @Test
    void lastUpdatedComesFromTheSyncPrefixLookup() {
        gates(true, true, true);
        List<String> asked = new ArrayList<>();
        DataSourceCatalog.SyncDates lookup = dates(Map.of(
                "https://calendrier.api.gouv.fr/jours-feries/", LocalDate.of(2026, 9, 27),
                "https://openholidaysapi.org/", LocalDate.of(2026, 9, 28)), Map.of(), asked, null);

        Map<String, String> updated = new HashMap<>();
        real().active(lookup).forEach(s -> updated.put(s.id(), s.lastUpdated()));

        assertThat(asked).containsExactly("https://calendrier.api.gouv.fr/jours-feries/",
                "https://data.education.gouv.fr/explore/dataset/fr-en-calendrier-scolaire/",
                "https://openholidaysapi.org/");
        assertThat(updated.get("calendrier-api-gouv")).isEqualTo("2026-09-27");
        assertThat(updated.get("openholidays")).isEqualTo("2026-09-28");
        assertThat(updated).containsEntry("fr-en-calendrier-scolaire", null)
                .containsEntry("iana-tz", null).containsEntry("openjdk-hijrah", null)
                .containsEntry("openweather", null).containsEntry("wikimedia-pageviews", null);
    }

    @Test
    void lastUpdatedComesFromTheSyncSourceLookup() {
        gates(true, true, true, true);
        football(true, "k");
        openEvents(true, "oa_pk_k", true);
        prim.setApiKey("prim-key");
        List<String> askedPrefixes = new ArrayList<>();
        List<String> askedSources = new ArrayList<>();
        DataSourceCatalog.SyncDates lookup = dates(Map.of(), Map.of(
                "openagenda", LocalDate.of(2026, 9, 29),
                "quefaireaparis", LocalDate.of(2026, 9, 30),
                "wikimedia", LocalDate.of(2026, 9, 28),
                "idfm-prim", LocalDate.of(2026, 10, 7)), askedPrefixes, askedSources);

        Map<String, String> updated = new HashMap<>();
        real().active(lookup).forEach(s -> updated.put(s.id(), s.lastUpdated()));

        assertThat(askedSources).containsExactly("wikimedia", "openagenda", "quefaireaparis", "idfm-prim");
        // the prefix entries only: iana-tz, openjdk-hijrah and openweather are never looked up
        assertThat(askedPrefixes).containsExactly("https://calendrier.api.gouv.fr/jours-feries/",
                "https://data.education.gouv.fr/explore/dataset/fr-en-calendrier-scolaire/",
                "https://openholidaysapi.org/", "https://api.football-data.org/v4/competitions/");
        assertThat(updated).containsEntry("openagenda", "2026-09-29")
                .containsEntry("quefaireaparis", "2026-09-30")
                .containsEntry("wikimedia-pageviews", "2026-09-28")
                .containsEntry("idfm-prim", "2026-10-07")
                .containsEntry("iana-tz", null).containsEntry("openjdk-hijrah", null)
                .containsEntry("openweather", null);
    }

    // --- load failures, each from an inline file ---

    private static final String VALID = """
            reviewedOn: 2026-09-30
            sources:
              - id: one
                name: One
                usedFor: [weather]
                licence: CC BY 4.0
                licenceUrl: https://creativecommons.org/licenses/by/4.0/
                creditLine: Data by One
                url: https://one.example/
                gate: weather
            """;

    private DataSourceCatalog parse(String yaml) {
        return DataSourceCatalog.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), gates);
    }

    @Test
    void inlineValidFileLoads() {
        predictor.setWeatherEnabled(true);
        assertThat(parse(VALID).active(NO_DATES)).extracting(PublicDataSource::id).containsExactly("one");
    }

    static Stream<Arguments> invalidFiles() {
        String second = VALID.substring(VALID.indexOf("  - id: one"));
        return Stream.of(
                Arguments.of("nonHttpsSyncPrefix",
                        VALID.replace("    gate: weather", "    syncPrefix: http://one.example/\n    gate: weather"),
                        List.of("one", "syncPrefix")),
                Arguments.of("unknownSyncSource",
                        VALID.replace("    gate: weather", "    syncSource: tides\n    gate: weather"),
                        List.of("one", "syncSource")),
                Arguments.of("syncPrefixWithSyncSource", VALID.replace("    gate: weather",
                                "    syncPrefix: https://one.example/\n    syncSource: openagenda\n    gate: weather"),
                        List.of("one", "mutually exclusive")),
                Arguments.of("blankSyncSource",
                        VALID.replace("    gate: weather", "    syncSource: \"  \"\n    gate: weather"),
                        List.of("one", "syncSource")),
                Arguments.of("unknownGate", VALID.replace("gate: weather", "gate: tides"), List.of("one", "gate")),
                Arguments.of("blankRequiredField", VALID.replace("creditLine: Data by One", "creditLine: \"  \""),
                        List.of("one", "creditLine")),
                Arguments.of("missingRequiredField", VALID.replace("    licence: CC BY 4.0\n", ""),
                        List.of("one", "licence")),
                Arguments.of("nonHttpsUrl", VALID.replace("url: https://one.example/", "url: http://one.example/"),
                        List.of("one", "url")),
                Arguments.of("nonHttpsLicenceUrl", VALID.replace("licenceUrl: https://", "licenceUrl: ftp://"),
                        List.of("one", "licenceUrl")),
                Arguments.of("duplicateId", VALID + second, List.of("one", "duplicate")),
                Arguments.of("unknownUsedFor", VALID.replace("usedFor: [weather]", "usedFor: [weather, tides]"),
                        List.of("one", "tides")),
                Arguments.of("emptyUsedFor", VALID.replace("usedFor: [weather]", "usedFor: []"),
                        List.of("one", "usedFor")),
                Arguments.of("missingReviewedOn", VALID.replace("reviewedOn: 2026-09-30\n", ""),
                        List.of("reviewedOn")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFiles")
    void invalidFileFailsLoad(String name, String yaml, List<String> fragments) {
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> fragments.forEach(f -> assertThat(e).hasMessageContaining(f)));
    }
}
