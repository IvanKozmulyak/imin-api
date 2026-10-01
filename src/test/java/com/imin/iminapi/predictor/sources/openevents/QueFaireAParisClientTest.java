package com.imin.iminapi.predictor.sources.openevents;

import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.Agenda;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.City;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource.Fetch;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource.RawEvent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QueFaireAParisClientTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO = LocalDate.of(2027, 1, 29);
    private static final City PARIS = new City("paris", "FR", List.of("paris"), List.of(), true);
    private static final String BASE =
            "https://opendata.paris.fr/api/explore/v2.1/catalog/datasets/que-faire-a-paris-/records";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final List<String> requested = new ArrayList<>();

    private QueFaireAParisClient client() {
        return new QueFaireAParisClient(builder);
    }

    private static String fixture() {
        try {
            return new ClassPathResource("predictor/openevents/quefaireaparis-records.json")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One record in the real field shape, with overrides; a null value removes the field. */
    private static String record(Map<String, String> overrides) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("id", "\"500\"");
        f.put("url", "\"https://www.paris.fr/evenements/soiree-jazz-500\"");
        f.put("title", "\"Soirée jazz\"");
        f.put("date_start", "\"2026-10-10T20:00:00+00:00\"");
        f.put("date_end", "\"2026-10-10T23:00:00+00:00\"");
        f.put("occurrences", "\"2026-10-10T20:00:00+02:00_2026-10-10T23:00:00+02:00\"");
        f.put("address_zipcode", "\"75011\"");
        f.put("address_city", "\"Paris\"");
        f.put("qfap_tags", "\"Concert;Festival\"");
        f.put("locations", "[{\"location_type\":\"address\",\"address_city\":\"Paris\"}]");
        f.putAll(overrides);
        StringBuilder sb = new StringBuilder("{");
        f.forEach((k, v) -> {
            if (v == null) return;
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(k).append("\":").append(v);
        });
        return sb.append('}').toString();
    }

    private static String page(int total, String... records) {
        return "{\"total_count\":" + total + ",\"results\":[" + String.join(",", records) + "]}";
    }

    private void respond(String body) {
        server.expect(req -> requested.add(URLDecoder.decode(req.getURI().toString(), StandardCharsets.UTF_8)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private Fetch fetchOne(String record) {
        respond(page(1, record));
        Fetch f = client().fetch(PARIS, FROM, TO);
        server.verify();
        return f;
    }

    private void dropped(Map<String, String> overrides, String reason) {
        Fetch f = fetchOne(record(overrides));
        assertThat(f.events()).as(reason).isEmpty();
        assertThat(f.dropped()).as(reason).containsEntry(reason, 1);
    }

    private static Map<String, String> without(String key) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, null);
        return m;
    }

    @Test
    void realFixtureParses() {
        respond(fixture());

        Fetch f = client().fetch(PARIS, FROM, TO);

        server.verify();
        assertThat(requested.get(0)).startsWith(BASE + "?")
                .contains("where=date_end >= date'2026-09-21' AND date_start <= date'2027-01-30'",
                        "order_by=id", "limit=100", "offset=0", "select=id,url,title,date_start,date_end,occurrences,"
                                + "address_zipcode,address_city,qfap_tags,locations")
                .doesNotContain("description");
        assertThat(f.events()).extracting(RawEvent::sourceEventId).containsExactly("23336", "22830", "22177", "11766");
        RawEvent concert = f.events().get(0);
        assertThat(concert.title()).startsWith("Concert commémoratif");
        assertThat(concert.url()).startsWith("https://www.paris.fr/evenements/");
        assertThat(concert.nights()).containsExactly(LocalDate.of(2026, 10, 30));
        assertThat(concert.keywords()).containsExactly("Concert");
        assertThat(f.events().get(1).nights()).containsExactly(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 17));
        // zip 75018 with no city name is still Paris
        assertThat(f.events().get(2).nights()).containsExactly(LocalDate.of(2026, 10, 25));
        assertThat(f.events().get(3).nights()).containsExactly(LocalDate.of(2027, 1, 2));
        // 38623 has no address, 37301 is Puteaux, 41529 has no address and only a link
        assertThat(f.dropped()).containsEntry("city", 3);
        assertThat(f.partial()).isFalse();
    }

    @Test
    void onlyParisCovered() {
        QueFaireAParisClient c = client();
        assertThat(c.covers(PARIS)).isTrue();
        assertThat(c.covers(new City("lille", "FR", List.of("lille"),
                List.of(new Agenda(1L, "x", "X", "Licence Ouverte 2.0")), false))).isFalse();
        assertThat(c.id()).isEqualTo("quefaireaparis");
        assertThat(c.gate()).isEqualTo("quefaireaparis");
        // Past events leave the dataset (min date_end = today, checked 2026-10-01), so there is nothing to backfill.
        assertThat(c.backfills()).isFalse();
    }

    @Test
    void nonParisZipDropped() {
        dropped(Map.of("address_zipcode", "\"93400\"", "address_city", "\"Saint-Ouen-sur-Seine\""), "city");
        server.reset();
        dropped(Map.of("address_zipcode", "\"7501\"", "address_city", "\"Paris 01\""), "city");
        server.reset();
        assertThat(fetchOne(record(Map.of("address_zipcode", "\"75005\"", "address_city", "\"Paris 05\""))).events())
                .hasSize(1);
        server.reset();
        Map<String, String> noZip = without("address_zipcode");
        noZip.put("address_city", "\" PARIS \"");
        assertThat(fetchOne(record(noZip)).events()).hasSize(1);
    }

    @Test
    void occurrencesSplitIntoNights() {
        Fetch f = fetchOne(record(Map.of("occurrences", "\""
                + "2026-10-03T20:00:00+02:00_2026-10-03T23:00:00+02:00;"
                + "2026-10-04T01:30:00+02:00_2026-10-04T04:00:00+02:00;"
                + "2026-10-10T20:00:00+02:00_2026-10-10T23:00:00+02:00\"")));

        // 01:30 on the 4th belongs to the night of the 3rd, which is kept once
        assertThat(f.events().get(0).nights()).containsExactly(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 10));
    }

    @Test
    void occurrenceOffsetIsIgnoredWallClockIsParis() {
        // upstream labels every occurrence +02:00, winter ones too: 06:30 here is Paris wall time, not 05:30 CET
        Fetch f = fetchOne(record(Map.of("occurrences", "\"2026-12-12T06:30:00+02:00_2026-12-12T09:00:00+02:00\"")));

        assertThat(f.events().get(0).nights()).containsExactly(LocalDate.of(2026, 12, 12));
    }

    @Test
    void unparseableOccurrencesDropped() {
        dropped(Map.of("occurrences", "\"2026-10-03T20:00:00+02:00\""), "timings");
        server.reset();
        // an end a day or more before the begin is not the overnight quirk below
        dropped(Map.of("occurrences", "\"2026-10-03T20:00:00+02:00_2026-10-02T22:00:00+02:00\""), "timings");
    }

    @Test
    void overnightEndWrittenOnTheBeginDateRollsToNextDay() {
        // upstream writes 23:00 -> 01:30 with both on the begin date (84 such occurrences on 2026-10-01)
        Fetch f = fetchOne(record(Map.of("occurrences", "\""
                + "2026-10-06T23:00:00+02:00_2026-10-06T01:30:00+02:00;"
                + "2026-11-16T14:00:00+02:00_2026-11-16T00:00:00+02:00\"")));

        assertThat(f.events().get(0).nights()).containsExactly(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 11, 16));
    }

    @Test
    void fallsBackToStartEndWhenNoOccurrences() {
        Map<String, String> m = without("occurrences");
        m.put("date_start", "\"2026-10-10T20:00:00+00:00\"");
        m.put("date_end", "\"2026-10-11T02:00:00+00:00\"");
        assertThat(fetchOne(record(m)).events().get(0).nights()).containsExactly(LocalDate.of(2026, 10, 10));

        server.reset();
        Map<String, String> blank = new LinkedHashMap<>(m);
        blank.put("occurrences", "\"\"");
        assertThat(fetchOne(record(blank)).events().get(0).nights()).containsExactly(LocalDate.of(2026, 10, 10));

        server.reset();
        Map<String, String> missing = without("occurrences");
        missing.put("date_start", null);
        dropped(missing, "timings");
    }

    @Test
    void dayOnlyFallbackKeepsItsDate() {
        // 00:00–23:59:59 is a date with no time, not an event starting at midnight of the night before
        Map<String, String> m = without("occurrences");
        m.put("date_start", "\"2026-10-09T00:00:00+00:00\"");
        m.put("date_end", "\"2026-10-09T23:59:59+00:00\"");

        assertThat(fetchOne(record(m)).events().get(0).nights()).containsExactly(LocalDate.of(2026, 10, 9));
    }

    /** Occurrences in upstream's shape: Paris wall time, always labelled +02:00. */
    private static String occurrences(List<LocalDate> days, String begin, String end) {
        List<String> out = new ArrayList<>();
        for (LocalDate d : days) out.add(d + "T" + begin + ":00+02:00_" + d + "T" + end + ":00+02:00");
        return "\"" + String.join(";", out) + "\"";
    }

    private static List<LocalDate> days(LocalDate first, int count, int stepDays) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(first.plusDays((long) i * stepDays));
        return out;
    }

    @Test
    void dailyRunWithTwoNightsInWindowDropped() {
        dropped(Map.of("occurrences", occurrences(days(LocalDate.of(2026, 8, 24), 30, 1), "10:00", "18:00")), "run");
    }

    @Test
    void weekendFestivalKept() {
        Fetch f = fetchOne(record(Map.of("occurrences", occurrences(days(LocalDate.of(2026, 10, 9), 3, 1), "20:00", "23:00"))));

        assertThat(f.events().get(0).nights())
                .containsExactly(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11));
    }

    @Test
    void weeklyThursdayKept() {
        Fetch f = fetchOne(record(Map.of("occurrences", occurrences(days(LocalDate.of(2026, 10, 1), 10, 7), "20:00", "23:00"))));

        assertThat(f.events().get(0).nights()).hasSize(10);
    }

    @Test
    void fourNightsInSevenDropped() {
        dropped(Map.of("occurrences", occurrences(days(LocalDate.of(2026, 10, 6), 4, 1), "20:00", "23:00")), "run");
    }

    @Test
    void twiceWeeklyResidencyKept() {
        List<LocalDate> d = new ArrayList<>(days(LocalDate.of(2026, 9, 25), 18, 7));
        d.addAll(days(LocalDate.of(2026, 9, 26), 18, 7));
        d.sort(null);

        assertThat(fetchOne(record(Map.of("occurrences", occurrences(d, "22:00", "23:30")))).events().get(0).nights())
                .hasSize(36);
    }

    @Test
    void threeTimesAWeekDropped() {
        List<LocalDate> d = new ArrayList<>();
        for (LocalDate mon : days(LocalDate.of(2026, 9, 21), 8, 7)) d.addAll(List.of(mon, mon.plusDays(2), mon.plusDays(4)));
        dropped(Map.of("occurrences", occurrences(d, "20:00", "23:00")), "run");
    }

    @Test
    void dateOnlyOccurrenceKeepsItsDate() {
        // 75 occurrences on 2026-10-01 are 00:00-23:59: a date, not the small hours of the night before
        Fetch f = fetchOne(record(Map.of("occurrences", "\"2026-11-11T00:00:00+02:00_2026-11-11T23:59:00+02:00\"")));

        assertThat(f.events().get(0).nights()).containsExactly(LocalDate.of(2026, 11, 11));
    }

    @Test
    void longRunDropped() {
        Map<String, String> m = without("occurrences");
        m.put("date_start", "\"2026-10-09T00:00:00+00:00\"");
        m.put("date_end", "\"2026-10-24T23:59:59+00:00\"");
        dropped(m, "long_run");
        server.reset();
        dropped(Map.of("occurrences", "\"2026-10-03T10:00:00+02:00_2026-10-04T10:00:01+02:00\""), "long_run");
    }

    @Test
    void onlineOnlyDropped() {
        dropped(Map.of("locations", "[{\"location_type\":\"url\",\"url\":\"https://example.org\"}]"), "online");
        server.reset();
        dropped(Map.of("locations", "[]"), "online");
        server.reset();
        dropped(without("locations"), "online");
        server.reset();
        dropped(Map.of("locations", "[{\"location_type\":\"hologram\"}]"), "online");
        server.reset();
        for (String type : List.of("address", "lieu", "service", "text")) {
            server.reset();
            assertThat(fetchOne(record(Map.of("locations",
                    "[{\"location_type\":\"url\"},{\"location_type\":\"" + type + "\"}]"))).events()).as(type).hasSize(1);
        }
    }

    @Test
    void blankTitleDropped() {
        dropped(Map.of("title", "\"  \""), "title");
        server.reset();
        dropped(Map.of("url", "\"\""), "no_url");
        server.reset();
        dropped(Map.of("id", "\"\""), "no_id");
    }

    @Test
    void licenceIsOdbl() {
        RawEvent e = fetchOne(record(Map.of())).events().get(0);

        assertThat(e.licence()).isEqualTo("ODbL 1.0");
        assertThat(client().licence()).isEqualTo("ODbL 1.0");
        assertThat(OpenEventSource.LICENCES).contains(QueFaireAParisClient.LICENCE);
        assertThat(e.credit()).isNull();
        assertThat(e.keywords()).containsExactly("Concert", "Festival");
    }

    @Test
    void pagesByOffsetUntilTotal() {
        respond(page(150, record(Map.of())));
        respond(page(150, record(Map.of("id", "\"501\""))));

        Fetch f = client().fetch(PARIS, FROM, TO);

        server.verify();
        assertThat(requested.get(1)).contains("offset=100");
        assertThat(f.events()).extracting(RawEvent::sourceEventId).containsExactly("500", "501");
        assertThat(f.partial()).isFalse();
    }

    @Test
    void offsetCapStopsAndReportsPartial() {
        int pages = QueFaireAParisClient.MAX_OFFSET_PLUS_LIMIT / QueFaireAParisClient.LIMIT;
        server.expect(ExpectedCount.times(pages), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withSuccess(page(20000, record(Map.of())), MediaType.APPLICATION_JSON));

        Fetch f = client().fetch(PARIS, FROM, TO);

        server.verify();
        assertThat(QueFaireAParisClient.MAX_OFFSET_PLUS_LIMIT).isEqualTo(10000);
        assertThat(pages).isEqualTo(100);
        assertThat(f.partial()).isTrue();
    }

    @Test
    void tooManyRequestsThrowsRateLimited() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client().fetch(PARIS, FROM, TO)).isInstanceOf(OpenEventsRateLimitedException.class);
    }

    @Test
    void serverErrorOrNonJsonThrows() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> client().fetch(PARIS, FROM, TO)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("502");
        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE))).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client().fetch(PARIS, FROM, TO)).isInstanceOf(IllegalStateException.class);
    }
}
