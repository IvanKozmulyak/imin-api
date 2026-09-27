package com.imin.iminapi.audienceplan.opendata;

import com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.METZ;
import static com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.NANCY;
import static com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.THIONVILLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IgssFrontaliersFetcherTest {

    private static final List<Object> HEADER = List.of("Date de référence", "Région", "Département",
            "Arrondissement", "Commune", "Genre", "Statut", "Nombre de personnes en emploi");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T08:00:00Z"));
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final IgssFrontaliersFetcher fetcher = new IgssFrontaliersFetcher(builder.build(), clock);

    private void respondWith(byte[] xlsx) {
        server.expect(once(), requestTo(IgssFrontaliersFetcher.URL))
                .andRespond(withSuccess(xlsx, MediaType.APPLICATION_OCTET_STREAM));
    }

    @Test
    void recordedFileGivesTheLatestDateForEachCity() {
        respondWith(OpenDataFixtures.bytes("igss_f_trimmed.xlsx"));

        FetchedFigure metz = fetcher.fetch(METZ);

        assertThat(metz.headline()).isEqualTo(6_330L);
        assertThat(metz.refPeriod()).isEqualTo("2026-03-31");
        assertThat(metz.figures()).containsEntry("employed_in_luxembourg", 6_330L);
        assertThat(metz.sourceUrl()).isEqualTo(IgssFrontaliersFetcher.URL);
        assertThat(fetcher.fetch(NANCY).headline()).isEqualTo(370L);
        assertThat(fetcher.fetch(THIONVILLE).headline()).isEqualTo(10_110L);
        server.verify(); // one download for all three cities
    }

    @Test
    void theParsedFileIsDownloadedAgainAfterAnHour() {
        server.expect(twice(), requestTo(IgssFrontaliersFetcher.URL))
                .andRespond(withSuccess(OpenDataFixtures.bytes("igss_f_trimmed.xlsx"), MediaType.APPLICATION_OCTET_STREAM));

        fetcher.fetch(METZ);
        clock.advance(IgssFrontaliersFetcher.MEMO_TTL.plus(Duration.ofSeconds(1)));
        fetcher.fetch(METZ);

        server.verify();
    }

    @Test
    void aSameNamedCommuneInAnotherDepartmentIsNotCounted() {
        respondWith(new XlsxBuilder().sheet(IgssFrontaliersFetcher.SHEET, List.of(HEADER,
                List.of("2026.03.31", "Grand Est", "Moselle", "Metz", "Metz", "Femmes", "Salariés", 10),
                List.of("2026.03.31", "Grand Est", "Moselle", "Metz", "Metz", "Hommes", "Salariés", 5),
                List.of("2026.03.31", "Grand Est", "Autre", "X", "Metz", "Hommes", "Salariés", 1000))).build());

        assertThat(fetcher.fetch(METZ).headline()).isEqualTo(15L);
    }

    @Test
    void olderDatesAndAggregateRowsAreIgnored() {
        respondWith(new XlsxBuilder().sheet(IgssFrontaliersFetcher.SHEET, List.of(HEADER,
                List.of("2025.09.30", "Grand Est", "Moselle", "Metz", "Metz", "Femmes", "Salariés", 99),
                List.of("2026.03.31", "Grand Est", "Moselle", "Metz", "Metz", "Femmes", "Salariés", 7),
                java.util.Arrays.asList("2026.03.31", "Grand Est", "Moselle", null, null, "Femmes", "Salariés", 500),
                List.of("2025.09.30", "Grand Est", "Moselle", "Metz", "Metz", "Hommes", "Salariés", 99))).build());

        FetchedFigure f = fetcher.fetch(METZ);

        assertThat(f.headline()).isEqualTo(7L);
        assertThat(f.refPeriod()).isEqualTo("2026-03-31");
    }

    @Test
    void aCityMissingFromTheFileFails() {
        respondWith(new XlsxBuilder().sheet(IgssFrontaliersFetcher.SHEET, List.of(HEADER,
                List.of("2026.03.31", "Grand Est", "Moselle", "Metz", "Metz", "Femmes", "Salariés", 7))).build());

        assertThatThrownBy(() -> fetcher.fetch(NANCY)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("no row for nancy");
    }

    @Test
    void aRowWithoutACountFailsRatherThanCountingZero() {
        respondWith(new XlsxBuilder().sheet(IgssFrontaliersFetcher.SHEET, List.of(HEADER,
                java.util.Arrays.asList("2026.03.31", "Grand Est", "Moselle", "Metz", "Metz", "Femmes", "Salariés", null)))
                .build());

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("without a count");
    }

    @Test
    void aNonNumericCountFails() {
        respondWith(new XlsxBuilder().sheet(IgssFrontaliersFetcher.SHEET, List.of(HEADER,
                List.of("2026.03.31", "Grand Est", "Moselle", "Metz", "Metz", "Femmes", "Salariés", "n/a"))).build());

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void aChangedHeaderFails() {
        respondWith(new XlsxBuilder().sheet(IgssFrontaliersFetcher.SHEET, List.of(List.of("Date", "Commune"))).build());

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("header changed");
    }

    @Test
    void aSheetWithOnlyAHeaderFails() {
        respondWith(new XlsxBuilder().sheet(IgssFrontaliersFetcher.SHEET, List.of(HEADER)).build());

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("no commune rows");
    }

    @Test
    void aWorkbookWithoutTheSourceSheetFails() {
        respondWith(new XlsxBuilder().sheet("Tableau dynamique", List.of(HEADER)).build());

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Sheet not found");
    }

    @Test
    void anOversizeDownloadFails() {
        respondWith(new byte[IgssFrontaliersFetcher.MAX_DOWNLOAD_BYTES + 1]);

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("larger than expected");
    }

    @Test
    void anEmptyDownloadFails() {
        respondWith(new byte[0]);

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void aServerErrorFails() {
        server.expect(requestTo(IgssFrontaliersFetcher.URL)).andRespond(withServerError());

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("500");
    }
}
