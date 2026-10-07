package com.imin.iminapi.predictor.sources.wikimedia;

import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.MonthViews;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.Result;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.WikimediaRateLimitedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WikimediaPageviewsClientTest {

    private static final String PROJECT = "fr.wikipedia";
    private static final String ARTICLE = "Techno";
    private static final YearMonth FROM = YearMonth.of(2025, 9);
    private static final YearMonth TO = YearMonth.of(2026, 9);
    // Path shape confirmed by a real GET on 2026-10-01 (monthly, start YYYYMM0100, end YYYYMM<last day>00).
    private static final String URL = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/"
            + "fr.wikipedia/all-access/user/Techno/monthly/2025090100/2026093000";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private WikimediaPageviewsClient client() {
        return new WikimediaPageviewsClient(builder, new WikimediaProperties());
    }

    private static String item(String timestamp, long views) {
        return item(PROJECT, ARTICLE, "monthly", "all-access", "user", timestamp, String.valueOf(views));
    }

    private static String item(String project, String article, String granularity, String access, String agent,
                               String timestamp, String views) {
        return "{\"project\":\"" + project + "\",\"article\":\"" + article + "\",\"granularity\":\"" + granularity
                + "\",\"timestamp\":\"" + timestamp + "\",\"access\":\"" + access + "\",\"agent\":\"" + agent
                + "\"" + (views == null ? "" : ",\"views\":" + views) + "}";
    }

    private static String body(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private List<MonthViews> fetch(String json) {
        server.expect(requestTo(URL)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        Result r = client().fetch(PROJECT, ARTICLE, FROM, TO);
        server.verify();
        assertThat(r.missing()).isFalse();
        return r.months();
    }

    private static MonthViews mv(int year, int month, long views) {
        return new MonthViews(YearMonth.of(year, month), views);
    }

    @Test
    void userAgentHeaderSent() {
        server.expect(requestTo(URL))
                .andExpect(header("User-Agent", "imin-api/1.0 (+https://imin.wtf; ops@imin.wtf) predictor-trends"))
                .andRespond(withSuccess(body(item("2025090100", 5)), MediaType.APPLICATION_JSON));

        client().fetch(PROJECT, ARTICLE, FROM, TO);

        server.verify();
    }

    @Test
    void requestIsMonthlyUserAllAccessOverTheRange() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body(item("2025090100", 5)), MediaType.APPLICATION_JSON));

        client().fetch(PROJECT, ARTICLE, FROM, TO);

        server.verify();
        // February of a leap year ends on the 29th.
        assertThat(WikimediaPageviewsClient.uri("de.wikipedia", "Techno", YearMonth.of(2027, 3), YearMonth.of(2028, 2)))
                .isEqualTo(URI.create("https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/"
                        + "de.wikipedia/all-access/user/Techno/monthly/2027030100/2028022900"));
    }

    @Test
    void titleEncodedAsOnePathSegment() {
        URI uri = WikimediaPageviewsClient.uri("en.wikipedia", "AC/DC & Co", FROM, TO);

        assertThat(uri.getRawPath()).isEqualTo("/api/rest_v1/metrics/pageviews/per-article/"
                + "en.wikipedia/all-access/user/AC%2FDC_%26_Co/monthly/2025090100/2026093000");
    }

    @Test
    void fetchSendsTheEncodedTitle() {
        YearMonth month = YearMonth.of(2026, 8);
        server.expect(requestTo(URI.create("https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/"
                        + "nl.wikipedia/all-access/user/Moderne_r%26b/monthly/2026080100/2026083100")))
                .andRespond(withSuccess(body(item("nl.wikipedia", "Moderne_r&b", "monthly", "all-access", "user",
                        "2026080100", "7")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URI.create("https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/"
                        + "uk.wikipedia/all-access/user/%D0%A2%D0%B5%D1%85%D0%BD%D0%BE/monthly/2026080100/2026083100")))
                .andRespond(withSuccess(body(item("uk.wikipedia", "Техно", "monthly", "all-access", "user",
                        "2026080100", "9")), MediaType.APPLICATION_JSON));

        WikimediaPageviewsClient c = client();
        Result rb = c.fetch("nl.wikipedia", "Moderne_r&b", month, month);
        Result techno = c.fetch("uk.wikipedia", "Техно", month, month);

        server.verify();
        assertThat(rb.months()).containsExactly(new MonthViews(month, 7));
        assertThat(techno.months()).containsExactly(new MonthViews(month, 9));
    }

    @Test
    void notFoundGivesMissing() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":404,\"title\":\"Not Found\"}"));

        Result r = client().fetch(PROJECT, ARTICLE, FROM, TO);

        server.verify();
        assertThat(r.missing()).isTrue();
        assertThat(r.months()).isEmpty();
    }

    @Test
    void serverErrorThrows() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client().fetch(PROJECT, ARTICLE, FROM, TO))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(WikimediaRateLimitedException.class)
                .hasMessageContaining("500");
        server.verify();
    }

    @Test
    void notJsonBodyThrows() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client().fetch(PROJECT, ARTICLE, FROM, TO)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tooManyRequestsThrowsRateLimited() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client().fetch(PROJECT, ARTICLE, FROM, TO))
                .isInstanceOf(WikimediaRateLimitedException.class);
        server.verify();
    }

    /** Items next to a valid September item that must not become a month. */
    static Stream<Arguments> droppedItems() {
        return Stream.of(
                Arguments.of("other agent", List.of(item(PROJECT, ARTICLE, "monthly", "all-access", "spider", "2025100100", "900"))),
                Arguments.of("other access", List.of(item(PROJECT, ARTICLE, "monthly", "mobile-web", "user", "2025100100", "900"))),
                Arguments.of("other granularity", List.of(item(PROJECT, ARTICLE, "daily", "all-access", "user", "2025100100", "900"))),
                Arguments.of("other article or project", List.of(
                        item(PROJECT, "House_music", "monthly", "all-access", "user", "2025100100", "900"),
                        item("de.wikipedia", ARTICLE, "monthly", "all-access", "user", "2025100100", "900"))),
                Arguments.of("negative, missing or non-integer views", List.of(
                        item(PROJECT, ARTICLE, "monthly", "all-access", "user", "2025100100", "-3"),
                        item(PROJECT, ARTICLE, "monthly", "all-access", "user", "2025100100", null),
                        item(PROJECT, ARTICLE, "monthly", "all-access", "user", "2025100100", "\"12\""),
                        item(PROJECT, ARTICLE, "monthly", "all-access", "user", "2025100100", "1.5"))),
                Arguments.of("timestamp not the first of a month in range",
                        List.of(item("2025101500", 900), item("20251001", 900), item("2027010100", 900))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("droppedItems")
    void itemDropped(String name, List<String> extra) {
        List<String> items = new ArrayList<>(List.of(item("2025090100", 5)));
        items.addAll(extra);

        assertThat(fetch(body(items.toArray(String[]::new)))).containsExactly(mv(2025, 9, 5));
    }

    @Test
    void omittedMonthBeforeLatestIsZero() {
        List<MonthViews> out = fetch(body(item("2025090100", 5), item("2025120100", 7)));

        assertThat(out).containsExactly(mv(2025, 9, 5), mv(2025, 10, 0), mv(2025, 11, 0), mv(2025, 12, 7));
    }

    @Test
    void unpublishedMonthNotStoredAsZero() {
        List<MonthViews> out = fetch(body(item("2025110100", 3), item("2025100100", 4)));

        // Nothing after the latest returned month (2025-11) is filled; the months before it are.
        assertThat(out).containsExactly(mv(2025, 9, 0), mv(2025, 10, 4), mv(2025, 11, 3));
        List<YearMonth> months = new ArrayList<>();
        out.forEach(m -> months.add(m.month()));
        assertThat(months).noneMatch(m -> m.isAfter(YearMonth.of(2025, 11)));
    }

    @Test
    void userAgentWithoutContactFailsStartup() {
        WikimediaProperties props = new WikimediaProperties();
        props.setUserAgent("imin-api/1.0 predictor");

        assertThatThrownBy(() -> new WikimediaPageviewsClient(RestClient.builder(), props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("User-Agent");
    }
}
