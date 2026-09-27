package com.imin.iminapi.audienceplan.opendata;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.METZ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MesrAtlasFetcherTest {

    private static final String METZ_URL = "https://data.enseignementsup-recherche.gouv.fr/api/explore/v2.1/catalog/"
            + "datasets/fr-esr-atlas_regional-effectifs-d-etudiants-inscrits/records"
            + "?select=annee_universitaire%2Csum%28effectif%29+as+effectif%2Ccount%28*%29+as+n_rows"
            + "%2Ccount%28effectif%29+as+n_effectif"
            + "&where=geo_id%3D%2257463%22+and+niveau_geographique%3D%22Commune%22+and+regroupement%3D%22TOTAL%22"
            + "&group_by=annee_universitaire&order_by=annee_universitaire+desc&limit=1";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final MesrAtlasFetcher fetcher = new MesrAtlasFetcher(builder.build());

    @Test
    void metzFixtureIsTheServerSideSumOfTheLatestAcademicYear() {
        server.expect(requestTo(METZ_URL))
                .andRespond(withSuccess(OpenDataFixtures.text("mesr_metz.json"), MediaType.APPLICATION_JSON));

        FetchedFigure f = fetcher.fetch(METZ);

        assertThat(f.headline()).isEqualTo(20_588L);
        assertThat(f.refPeriod()).isEqualTo("2024-25");
        assertThat(f.figures()).containsEntry("students", 20_588L);
        assertThat(f.sourceUrl()).isEqualTo(METZ_URL);
        server.verify();
    }

    @Test
    void noRowsFailsInsteadOfReturningZero() {
        server.expect(requestTo(METZ_URL))
                .andRespond(withSuccess("{\"total_count\":0,\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("no enrolment");
    }

    @Test
    void aLatestYearWithoutASumFails() {
        String body = "{\"results\":[{\"annee_universitaire\":\"2024-25\",\"effectif\":null,\"n_rows\":4,\"n_effectif\":0}]}";
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("without effectif");
    }

    @Test
    void aYearWhereSomeRowsLackACountFailsInsteadOfUndercounting() {
        String body = "{\"results\":[{\"annee_universitaire\":\"2024-25\",\"effectif\":10,\"n_rows\":4,\"n_effectif\":3}]}";
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("without effectif");
    }

    @Test
    void aGroupWithoutRowCountsFails() {
        String body = "{\"results\":[{\"annee_universitaire\":\"2024-25\",\"effectif\":10}]}";
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("without effectif");
    }

    @Test
    void aServerErrorFails() {
        server.expect(requestTo(METZ_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class);
    }

    @Test
    void anUnreadableBodyFails() {
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Unreadable");
    }

    @Test
    void anEmptyBodyFails() {
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("Empty answer");
    }
}
