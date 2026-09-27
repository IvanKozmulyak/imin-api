package com.imin.iminapi.audienceplan.opendata;

import com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.MutableClock;
import com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.RecordingSleeper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static com.imin.iminapi.audienceplan.opendata.OpenDataFixtures.METZ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class InseeMelodiFetcherTest {

    private static final String METZ_URL =
            "https://api.insee.fr/melodi/data/DS_RP_TD_POPULATION_AGESEX_PRINC?GEO=COM-57463&SEX=_T&maxResult=10000";

    private final RecordingSleeper sleeper = new RecordingSleeper();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    // The clock never moves, so the throttle's own waits show up in the sleeper too.
    private final InseeMelodiFetcher fetcher = new InseeMelodiFetcher(builder.build(),
            new MelodiThrottle(new MutableClock(Instant.parse("2026-09-27T08:00:00Z")), sleeper), sleeper);

    @Test
    void metzFixtureGivesPeopleAged18To35AndTheTotal() {
        server.expect(requestTo(METZ_URL)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(OpenDataFixtures.text("melodi_metz_2023.json"), MediaType.APPLICATION_JSON));

        FetchedFigure f = fetcher.fetch(METZ);

        assertThat(f.headline()).isEqualTo(38_065L);
        assertThat(f.refPeriod()).isEqualTo("2023");
        assertThat(f.figures()).containsEntry("pop_18_35", 38_065L).containsEntry("pop_total", 122_572L);
        assertThat(f.sourceUrl()).isEqualTo(METZ_URL);
        server.verify();
    }

    @Test
    void theLatestCensusYearWins() {
        String body = """
                {"observations":[
                  %s, %s
                ],"paging":{}}""".formatted(obsBlock("2022", 1.0), obsBlock("2023", 2.0));
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        FetchedFigure f = fetcher.fetch(METZ);

        assertThat(f.refPeriod()).isEqualTo("2023");
        assertThat(f.headline()).isEqualTo(36L); // 18 ages x 2.0
        assertThat(f.figures()).containsEntry("pop_total", 999L);
    }

    @Test
    void aTotalMissingFromTheAnswerIsNullNotZero() {
        String body = "{\"observations\":[" + ages("2023", 1.0) + "],\"paging\":{}}";
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        FetchedFigure f = fetcher.fetch(METZ);

        assertThat(f.headline()).isEqualTo(18L);
        assertThat(f.figures()).containsEntry("pop_total", null);
    }

    @Test
    void observationsForOtherMeasuresOrSexesOrWithoutANumberAreIgnored() {
        String noise = """
                {"dimensions":{"TIME_PERIOD":"2024","SEX":"F","RP_MEASURE":"POP","AGE":"Y20"},"measures":{"OBS_VALUE_NIVEAU":{"value":5}}},
                {"dimensions":{"TIME_PERIOD":"2024","SEX":"_T","RP_MEASURE":"OTHER","AGE":"Y20"},"measures":{"OBS_VALUE_NIVEAU":{"value":5}}},
                {"dimensions":{"TIME_PERIOD":"2024","SEX":"_T","RP_MEASURE":"POP","AGE":"Y20"},"measures":{"OBS_VALUE_NIVEAU":{"value":null}}}""";
        String body = "{\"observations\":[" + ages("2023", 1.0) + "," + noise + "],\"paging\":{}}";
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(fetcher.fetch(METZ).refPeriod()).isEqualTo("2023");
    }

    @Test
    void noObservationsFailsInsteadOfReturningZero() {
        server.expect(requestTo(METZ_URL))
                .andRespond(withSuccess("{\"observations\":[],\"paging\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("no population");
    }

    @Test
    void aMissingSingleYearFails() {
        String body = "{\"observations\":[" + ages("2023", 1.0).replace("\"Y25\"", "\"Y99\"") + "],\"paging\":{}}";
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("missing age 25");
    }

    @Test
    void aPagedAnswerFailsRatherThanSummingPart() {
        String body = "{\"observations\":[" + ages("2023", 1.0) + "],\"paging\":{\"next\":\"https://x/page=2\"}}";
        server.expect(requestTo(METZ_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("paged");
    }

    @Test
    void rateLimitedThenOkWaitsRetryAfterAndSucceeds() {
        HttpHeaders h = new HttpHeaders();
        h.add("Retry-After", "7");
        server.expect(requestTo(METZ_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(h));
        server.expect(requestTo(METZ_URL))
                .andRespond(withSuccess(OpenDataFixtures.text("melodi_metz_2023.json"), MediaType.APPLICATION_JSON));

        assertThat(fetcher.fetch(METZ).headline()).isEqualTo(38_065L);
        // backoff 7 s, then the throttle's 2 s slot for the retry
        assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void rateLimitedWithoutRetryAfterUsesTheDefaultBackoff() {
        server.expect(requestTo(METZ_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(METZ_URL))
                .andRespond(withSuccess(OpenDataFixtures.text("melodi_metz_2023.json"), MediaType.APPLICATION_JSON));

        fetcher.fetch(METZ);

        assertThat(sleeper.sleeps.get(0)).isEqualTo(InseeMelodiFetcher.DEFAULT_BACKOFF);
    }

    @Test
    void stillRateLimitedAfterThreeAttemptsFails() {
        server.expect(times(3), requestTo(METZ_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class)
                .hasMessageContaining("rate-limited");
        server.verify();
    }

    @Test
    void aServerErrorFailsWithoutRetry() {
        server.expect(requestTo(METZ_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> fetcher.fetch(METZ)).isInstanceOf(OpenDataFetchException.class);
        server.verify();
    }

    @Test
    void backoffReadsSecondsCapsThemAndFallsBackForADate() {
        assertThat(InseeMelodiFetcher.backoff("30")).isEqualTo(Duration.ofSeconds(30));
        assertThat(InseeMelodiFetcher.backoff("100000")).isEqualTo(InseeMelodiFetcher.MAX_BACKOFF);
        assertThat(InseeMelodiFetcher.backoff("-1")).isEqualTo(InseeMelodiFetcher.DEFAULT_BACKOFF);
        assertThat(InseeMelodiFetcher.backoff("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(InseeMelodiFetcher.DEFAULT_BACKOFF);
        assertThat(InseeMelodiFetcher.backoff(null)).isEqualTo(InseeMelodiFetcher.DEFAULT_BACKOFF);
    }

    private static String obsBlock(String year, double perAge) {
        return ages(year, perAge) + "," + obs(year, "_T", 999.0);
    }

    private static String ages(String year, double perAge) {
        StringBuilder sb = new StringBuilder();
        for (int age = 18; age <= 35; age++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(obs(year, "Y" + age, perAge));
        }
        return sb.toString();
    }

    private static String obs(String year, String age, double value) {
        return "{\"dimensions\":{\"TIME_PERIOD\":\"" + year + "\",\"SEX\":\"_T\",\"RP_MEASURE\":\"POP\",\"AGE\":\""
                + age + "\"},\"measures\":{\"OBS_VALUE_NIVEAU\":{\"value\":" + value + "}}}";
    }
}
