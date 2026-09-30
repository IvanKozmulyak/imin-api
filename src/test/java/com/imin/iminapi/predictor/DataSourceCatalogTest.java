package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.calendar.CalendarSyncProperties;
import com.imin.iminapi.predictor.calendar.OpenHolidaysSync;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PublicDataSourcesResponse.PublicDataSource;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceGates;
import org.junit.jupiter.api.Test;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceCatalogTest {

    private final CalendarSyncProperties calendar = new CalendarSyncProperties();
    private final PredictorProperties predictor = new PredictorProperties();
    private final DateCheckProperties dateCheck = new DateCheckProperties();
    private final SourceGates gates = new SourceGates(calendar, predictor, dateCheck);

    private static final Function<String, Optional<LocalDate>> NO_DATES = prefix -> Optional.empty();

    private void gates(boolean dateCheckOn, boolean syncOn, boolean weatherOn) {
        dateCheck.setEnabled(dateCheckOn);
        calendar.setSyncEnabled(syncOn);
        predictor.setWeatherEnabled(weatherOn);
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

        DataSourceCatalog catalog = real();
        List<PublicDataSource> active = catalog.active(NO_DATES);

        assertThat(catalog.reviewedOn()).isEqualTo("2026-09-30");
        assertThat(active).extracting(PublicDataSource::id).containsExactlyElementsOf(yamlIds());
        assertThat(active).extracting(PublicDataSource::id).containsExactly(
                "calendrier-api-gouv", "fr-en-calendrier-scolaire", "openholidays", "iana-tz", "openjdk-hijrah",
                "open-meteo");
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
    void dateCheckOffHidesCalendarSources() {
        gates(false, true, true);

        assertThat(activeIds()).containsExactly("open-meteo");
    }

    @Test
    void calendarSyncOffHidesCalendarSources() {
        gates(true, false, true);

        assertThat(activeIds()).containsExactly("open-meteo");
    }

    @Test
    void weatherOffHidesOpenMeteo() {
        gates(true, true, false);

        assertThat(activeIds()).containsExactly(
                "calendrier-api-gouv", "fr-en-calendrier-scolaire", "openholidays", "iana-tz", "openjdk-hijrah");
    }

    @Test
    void allGatesOffReturnsEmpty() {
        gates(false, false, false);

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
        Function<String, Optional<LocalDate>> lookup = prefix -> {
            asked.add(prefix);
            if (prefix.startsWith("https://calendrier.api.gouv.fr/")) return Optional.of(LocalDate.of(2026, 9, 27));
            if (prefix.equals("https://openholidaysapi.org/")) return Optional.of(LocalDate.of(2026, 9, 28));
            return Optional.empty();
        };

        Map<String, String> updated = new HashMap<>();
        real().active(lookup).forEach(s -> updated.put(s.id(), s.lastUpdated()));

        assertThat(asked).containsExactly("https://calendrier.api.gouv.fr/jours-feries/",
                "https://data.education.gouv.fr/explore/dataset/fr-en-calendrier-scolaire/",
                "https://openholidaysapi.org/");
        assertThat(updated.get("calendrier-api-gouv")).isEqualTo("2026-09-27");
        assertThat(updated.get("openholidays")).isEqualTo("2026-09-28");
        assertThat(updated).containsEntry("fr-en-calendrier-scolaire", null)
                .containsEntry("iana-tz", null).containsEntry("openjdk-hijrah", null)
                .containsEntry("open-meteo", null);
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

    @Test
    void nonHttpsSyncPrefixFailsLoad() {
        assertThatThrownBy(() -> parse(VALID.replace("    gate: weather", "    syncPrefix: http://one.example/\n    gate: weather")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one").hasMessageContaining("syncPrefix");
    }

    @Test
    void unknownGateFailsLoad() {
        assertThatThrownBy(() -> parse(VALID.replace("gate: weather", "gate: tides")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one").hasMessageContaining("gate");
    }

    @Test
    void blankRequiredFieldFailsLoad() {
        assertThatThrownBy(() -> parse(VALID.replace("creditLine: Data by One", "creditLine: \"  \"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one").hasMessageContaining("creditLine");
    }

    @Test
    void missingRequiredFieldFailsLoad() {
        assertThatThrownBy(() -> parse(VALID.replace("    licence: CC BY 4.0\n", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one").hasMessageContaining("licence");
    }

    @Test
    void nonHttpsUrlFailsLoad() {
        assertThatThrownBy(() -> parse(VALID.replace("url: https://one.example/", "url: http://one.example/")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one").hasMessageContaining("url");
        assertThatThrownBy(() -> parse(VALID.replace("licenceUrl: https://", "licenceUrl: ftp://")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one").hasMessageContaining("licenceUrl");
    }

    @Test
    void duplicateIdFailsLoad() {
        String second = VALID.substring(VALID.indexOf("  - id: one"));
        assertThatThrownBy(() -> parse(VALID + second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one").hasMessageContaining("duplicate");
    }

    @Test
    void unknownUsedForFailsLoad() {
        assertThatThrownBy(() -> parse(VALID.replace("usedFor: [weather]", "usedFor: [weather, tides]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one").hasMessageContaining("tides");
    }

    @Test
    void emptyUsedForFailsLoad() {
        assertThatThrownBy(() -> parse(VALID.replace("usedFor: [weather]", "usedFor: []")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one").hasMessageContaining("usedFor");
    }

    @Test
    void missingReviewedOnFailsLoad() {
        assertThatThrownBy(() -> parse(VALID.replace("reviewedOn: 2026-09-30\n", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reviewedOn");
    }
}
