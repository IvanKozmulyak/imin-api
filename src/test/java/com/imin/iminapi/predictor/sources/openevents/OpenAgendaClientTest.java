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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAgendaClientTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO = LocalDate.of(2027, 1, 29);
    private static final City LILLE = new City("lille", "FR", List.of("lille", "hellemmes", "lomme"),
            List.of(new Agenda(57621068L, "ville-de-lille", "Ville de Lille", "Licence Ouverte 2.0")), false);
    private static final String KEY = "oa_pk_testkey";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final List<String> requested = new ArrayList<>();

    private OpenAgendaClient client() {
        OpenEventsProperties props = new OpenEventsProperties();
        props.setOpenagendaEnabled(true);
        props.setOpenagendaApiKey(KEY);
        return new OpenAgendaClient(builder, props);
    }

    private static String fixture() {
        try {
            return new ClassPathResource("predictor/openevents/openagenda-events.json")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One event in the real field shape, with overrides; a null value removes the field. */
    private static String event(Map<String, String> overrides) {
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("uid", "42");
        f.put("slug", "\"soiree-techno\"");
        f.put("title", "\"Soirée techno\"");
        f.put("keywords", "[\"techno\"]");
        f.put("status", "1");
        f.put("attendanceMode", "1");
        f.put("state", "2");
        f.put("location", "{\"city\":\"Lille\",\"countryCode\":\"FR\"}");
        f.put("timings", "[{\"begin\":\"2026-10-10T22:00:00.000+02:00\",\"end\":\"2026-10-11T04:00:00.000+02:00\"}]");
        f.putAll(overrides);
        StringBuilder sb = new StringBuilder("{");
        f.forEach((k, v) -> {
            if (v == null) return;
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(k).append("\":").append(v);
        });
        return sb.append('}').toString();
    }

    private static String page(String after, String... events) {
        return "{\"total\":" + events.length + ",\"events\":[" + String.join(",", events) + "],\"after\":"
                + (after == null ? "null" : after) + ",\"success\":true}";
    }

    private void respond(String body) {
        server.expect(req -> requested.add(URLDecoder.decode(req.getURI().toString(), StandardCharsets.UTF_8)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private Fetch fetchOne(String event) {
        respond(page(null, event));
        Fetch f = client().fetch(LILLE, FROM, TO);
        server.verify();
        return f;
    }

    private void dropped(Map<String, String> overrides, String reason) {
        Fetch f = fetchOne(event(overrides));
        assertThat(f.events()).as(reason).isEmpty();
        assertThat(f.dropped()).as(reason).containsEntry(reason, 1);
    }

    @Test
    void realFixtureParses() {
        respond(fixture().replaceFirst("\"after\": \\[[^]]*]", "\"after\": null"));

        Fetch f = client().fetch(LILLE, FROM, TO);

        assertThat(f.partial()).isFalse();
        assertThat(f.events()).extracting(RawEvent::sourceEventId)
                .containsExactly("13287689", "26237876", "64958747", "72323021", "7509670");
        RawEvent rap = f.events().get(0);
        assertThat(rap.title()).isEqualTo("Nono La Grinta");
        assertThat(rap.url()).isEqualTo("https://openagenda.com/fr/ville-de-lille/events/nono-la-grinta");
        assertThat(rap.nights()).containsExactly(LocalDate.of(2026, 10, 16));
        assertThat(rap.keywords()).containsExactly("concert", "rap");
        assertThat(rap.licence()).isEqualTo("Licence Ouverte 2.0");
        assertThat(rap.credit()).isNull();
        assertThat(f.events().get(3).nights())
                .containsExactly(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11));
        // Saint-Ouen-sur-Seine is not a Lille alias; the online one has attendanceMode 2
        assertThat(f.dropped()).containsEntry("city", 1).containsEntry("attendance", 1);
    }

    @Test
    void keySentInKeyHeaderNotQuery() {
        server.expect(header("key", KEY))
                .andExpect(req -> assertThat(req.getURI().toString()).doesNotContain("key=").doesNotContain(KEY))
                .andExpect(req -> requested.add(URLDecoder.decode(req.getURI().toString(), StandardCharsets.UTF_8)))
                .andRespond(withSuccess(page(null, event(Map.of())), MediaType.APPLICATION_JSON));

        client().fetch(LILLE, FROM, TO);

        server.verify();
        assertThat(requested.get(0))
                .startsWith("https://api.openagenda.com/v2/agendas/57621068/events?")
                .contains("size=300", "monolingual=fr", "timings[gte]=2026-09-21T04:00:00Z",
                        "timings[lte]=2027-01-30T05:00:00Z", "includeFields[]=uid", "includeFields[]=status")
                .doesNotContain("description");
    }

    @Test
    void pagesFollowAfterCursor() {
        respond(page("[\"0\",\"000001792225800\",\"98272664\"]", event(Map.of())));
        respond(page(null, event(Map.of("uid", "43", "slug", "\"autre\""))));

        Fetch f = client().fetch(LILLE, FROM, TO);

        server.verify();
        assertThat(requested.get(1)).contains("after[]=0&after[]=000001792225800&after[]=98272664");
        assertThat(requested.get(0)).doesNotContain("after[]");
        assertThat(f.events()).extracting(RawEvent::sourceEventId).containsExactly("42", "43");
        assertThat(f.partial()).isFalse();
    }

    @Test
    void pageCapStopsAndReportsPartial() {
        server.expect(ExpectedCount.times(OpenAgendaClient.MAX_PAGES), requestTo(org.hamcrest.Matchers.startsWith(
                        "https://api.openagenda.com/v2/agendas/57621068/events")))
                .andRespond(withSuccess(page("[\"1\"]", event(Map.of())), MediaType.APPLICATION_JSON));

        Fetch f = client().fetch(LILLE, FROM, TO);

        server.verify();
        assertThat(OpenAgendaClient.MAX_PAGES).isEqualTo(20);
        assertThat(f.partial()).isTrue();
    }

    @Test
    void keptStatusesAndModes() {
        for (String status : List.of("1", "2", "5")) {
            for (String mode : List.of("1", "3")) {
                server.reset();
                Fetch f = fetchOne(event(Map.of("status", status, "attendanceMode", mode)));
                assertThat(f.events()).as(status + "/" + mode).hasSize(1);
            }
        }
    }

    @Test
    void cancelledDropped() {
        dropped(Map.of("status", "6"), "status");
    }

    @Test
    void postponedDropped() {
        dropped(Map.of("status", "4"), "status");
    }

    @Test
    void movedOnlineDropped() {
        dropped(Map.of("status", "3"), "status");
    }

    @Test
    void unknownStatusDropped() {
        dropped(Map.of("status", "9"), "status");
        server.reset();
        dropped(java.util.Collections.singletonMap("status", null), "status");
    }

    @Test
    void onlineAttendanceDropped() {
        dropped(Map.of("attendanceMode", "2"), "attendance");
    }

    @Test
    void missingAttendanceDropped() {
        dropped(java.util.Collections.singletonMap("attendanceMode", null), "attendance");
    }

    @Test
    void unpublishedStateDropped() {
        dropped(Map.of("state", "1"), "state");
        server.reset();
        assertThat(fetchOne(event(java.util.Collections.singletonMap("state", null))).events()).hasSize(1);
    }

    @Test
    void nonFrCountryDropped() {
        dropped(Map.of("location", "{\"city\":\"Lille\",\"countryCode\":\"BE\"}"), "country");
        server.reset();
        dropped(Map.of("location", "{\"city\":\"Lille\"}"), "country");
    }

    @Test
    void otherCityDropped() {
        dropped(Map.of("location", "{\"city\":\"Roubaix\",\"countryCode\":\"FR\"}"), "city");
        server.reset();
        assertThat(fetchOne(event(Map.of("location", "{\"city\":\" Lomme \",\"countryCode\":\"FR\"}"))).events())
                .hasSize(1);
    }

    @Test
    void blankCityDroppedNotDefaulted() {
        dropped(Map.of("location", "{\"city\":\" \",\"countryCode\":\"FR\"}"), "city");
        server.reset();
        dropped(Map.of("location", "{\"countryCode\":\"FR\"}"), "city");
    }

    @Test
    void blankTitleDropped() {
        dropped(Map.of("title", "\" \""), "title");
        server.reset();
        // multilingual title without monolingual: fr first, else the first language present
        Fetch f = fetchOne(event(Map.of("title", "{\"en\":\"Techno night\",\"de\":\"Technonacht\"}")));
        assertThat(f.events().get(0).title()).isEqualTo("Techno night");
        server.reset();
        Fetch fr = fetchOne(event(Map.of("title", "{\"en\":\"Techno night\",\"fr\":\"Nuit techno\"}")));
        assertThat(fr.events().get(0).title()).isEqualTo("Nuit techno");
    }

    @Test
    void blankSlugDropped() {
        dropped(Map.of("slug", "\"\""), "no_url");
    }

    @Test
    void timingOver24hDropped() {
        dropped(Map.of("timings", "[{\"begin\":\"2026-10-10T10:00:00.000+02:00\",\"end\":\"2026-10-11T10:00:01.000+02:00\"},"
                + "{\"begin\":\"2026-10-12T20:00:00.000+02:00\",\"end\":\"2026-10-12T22:00:00.000+02:00\"}]"), "long_run");
        server.reset();
        dropped(Map.of("timings", "[{\"begin\":\"nope\",\"end\":\"2026-10-11T10:00:00.000+02:00\"}]"), "timings");
        server.reset();
        dropped(Map.of("timings", "[]"), "timings");
    }

    @Test
    void nightsOutsideWindowIgnored() {
        Fetch f = fetchOne(event(Map.of("timings",
                "[{\"begin\":\"2026-09-20T20:00:00.000+02:00\",\"end\":\"2026-09-20T22:00:00.000+02:00\"},"
                        + "{\"begin\":\"2026-09-21T20:00:00.000+02:00\",\"end\":\"2026-09-21T22:00:00.000+02:00\"}]")));
        assertThat(f.events().get(0).nights()).containsExactly(FROM);
        server.reset();
        dropped(Map.of("timings", "[{\"begin\":\"2027-02-01T20:00:00.000+01:00\",\"end\":\"2027-02-01T22:00:00.000+01:00\"}]"),
                "no_night");
    }

    @Test
    void beforeSixAmBelongsToPreviousNight() {
        Fetch f = fetchOne(event(Map.of("timings", "["
                // summer time: 05:59 local is still Friday night, 06:00 is Saturday
                + "{\"begin\":\"2026-10-03T05:59:00.000+02:00\",\"end\":\"2026-10-03T07:00:00.000+02:00\"},"
                + "{\"begin\":\"2026-10-03T06:00:00.000+02:00\",\"end\":\"2026-10-03T08:00:00.000+02:00\"},"
                // DST ends 25 Oct 03:00: 02:30 CET (+01:00) is Saturday night
                + "{\"begin\":\"2026-10-25T02:30:00.000+01:00\",\"end\":\"2026-10-25T05:00:00.000+01:00\"},"
                // a UTC begin: 2026-11-07T05:30Z is 06:30 in Paris, so Saturday 7 Nov itself
                + "{\"begin\":\"2026-11-07T05:30:00.000Z\",\"end\":\"2026-11-07T08:00:00.000Z\"}]")));

        assertThat(f.events().get(0).nights()).containsExactly(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 10, 24), LocalDate.of(2026, 11, 7));
    }

    private static final java.time.ZoneId PARIS = java.time.ZoneId.of("Europe/Paris");

    /** One timing per day, beginHour to endHour Paris time, with the real offset of that date. */
    private static String timings(List<LocalDate> days, int beginHour, int endHour) {
        List<String> out = new ArrayList<>();
        for (LocalDate d : days) {
            out.add("{\"begin\":\"" + d.atTime(beginHour, 0).atZone(PARIS).toOffsetDateTime()
                    + "\",\"end\":\"" + d.atTime(endHour, 0).atZone(PARIS).toOffsetDateTime() + "\"}");
        }
        return "[" + String.join(",", out) + "]";
    }

    private static List<LocalDate> days(LocalDate first, int count, int stepDays) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(first.plusDays((long) i * stepDays));
        return out;
    }

    @Test
    void dailyRunWithTwoNightsInWindowDropped() {
        // 30 consecutive days 10-18h, 2026-08-24..09-22: only 21 and 22 Sep fall in the window
        dropped(Map.of("timings", timings(days(LocalDate.of(2026, 8, 24), 30, 1), 10, 18)), "run");
    }

    @Test
    void weekendFestivalKept() {
        Fetch f = fetchOne(event(Map.of("timings", timings(days(LocalDate.of(2026, 10, 9), 3, 1), 20, 23))));

        assertThat(f.events().get(0).nights())
                .containsExactly(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11));
    }

    @Test
    void weeklyThursdayKept() {
        Fetch f = fetchOne(event(Map.of("timings", timings(days(LocalDate.of(2026, 10, 1), 10, 7), 20, 23))));

        assertThat(f.events().get(0).nights()).hasSize(10).startsWith(LocalDate.of(2026, 10, 1))
                .endsWith(LocalDate.of(2026, 12, 3));
    }

    @Test
    void fourNightsInSevenDropped() {
        // Tuesday to Friday
        dropped(Map.of("timings", timings(days(LocalDate.of(2026, 10, 6), 4, 1), 20, 23)), "run");
    }

    private static List<LocalDate> friSat(LocalDate firstFriday, int weeks) {
        List<LocalDate> d = new ArrayList<>(days(firstFriday, weeks, 7));
        d.addAll(days(firstFriday.plusDays(1), weeks, 7));
        d.sort(null);
        return d;
    }

    @Test
    void twiceWeeklyResidencyAcrossTheWindowKept() {
        // Fridays and Saturdays for 18 weeks: 36 nights, all in the window
        Fetch f = fetchOne(event(Map.of("timings", timings(friSat(LocalDate.of(2026, 9, 25), 18), 22, 23))));

        assertThat(f.events().get(0).nights()).hasSize(36);
    }

    @Test
    void twiceWeeklyResidencyNearItsEndKept() {
        // same series from June: only its last four nights fall in the window
        Fetch f = fetchOne(event(Map.of("timings", timings(friSat(LocalDate.of(2026, 6, 5), 18), 22, 23))));

        assertThat(f.events().get(0).nights()).containsExactly(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26),
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3));
    }

    @Test
    void threeTimesAWeekDropped() {
        // Monday, Wednesday, Friday: never 4 in 7 days, but 13 inside 30
        List<LocalDate> d = new ArrayList<>();
        for (LocalDate mon : days(LocalDate.of(2026, 9, 21), 8, 7)) d.addAll(List.of(mon, mon.plusDays(2), mon.plusDays(4)));
        dropped(Map.of("timings", timings(d, 20, 23)), "run");
    }

    @Test
    void densityBoundary() {
        // Monday, Wednesday, Friday: never 4 in 7 days
        java.util.TreeSet<LocalDate> mwf = new java.util.TreeSet<>();
        for (LocalDate mon : days(LocalDate.of(2026, 10, 5), 5, 7)) mwf.addAll(List.of(mon, mon.plusDays(2), mon.plusDays(4)));
        java.util.TreeSet<LocalDate> twelve = new java.util.TreeSet<>(mwf.headSet(LocalDate.of(2026, 11, 2)));
        // four weeks, 12 nights over 26 days: kept
        assertThat(twelve).hasSize(12);
        assertThat(OpenEventSource.isRun(twelve)).isFalse();
        // the 13th (Monday 2 Nov) is day 28 of the same 30: dropped
        twelve.add(LocalDate.of(2026, 11, 2));
        assertThat(OpenEventSource.isRun(twelve)).isTrue();
    }

    @Test
    void tooManyRequestsThrowsRateLimited() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.openagenda.com/")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client().fetch(LILLE, FROM, TO)).isInstanceOf(OpenEventsRateLimitedException.class);
    }

    @Test
    void serverErrorThrows() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.openagenda.com/")))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client().fetch(LILLE, FROM, TO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("500")
                .hasMessageNotContaining(KEY);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.openagenda.com/")))
                .andRespond(withSuccess("<html>", MediaType.TEXT_HTML));
        assertThatThrownBy(() -> client().fetch(LILLE, FROM, TO)).isInstanceOf(IllegalStateException.class);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.openagenda.com/")))
                .andRespond(withSuccess("{\"total\":0}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client().fetch(LILLE, FROM, TO)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void descriptionNeverRead() {
        Fetch f = fetchOne(event(Map.of("title", "\"Atelier\"", "keywords", "[\"lecture\"]",
                "description", "\"Une soirée techno house\"", "longDescription", "\"techno techno\"")));

        RawEvent e = f.events().get(0);
        assertThat(e.title()).isEqualTo("Atelier");
        assertThat(e.keywords()).containsExactly("lecture");
        assertThat(GenreMatcher.load(new org.springframework.core.io.DefaultResourceLoader())
                .match(e.title(), e.keywords()).matched()).isFalse();
        assertThat(requested.get(0)).doesNotContain("description").doesNotContain("detailed");
    }

    @Test
    void identity() {
        OpenAgendaClient c = client();
        assertThat(c.id()).isEqualTo("openagenda");
        assertThat(c.gate()).isEqualTo("openagenda");
        assertThat(c.licence()).isEqualTo("Licence Ouverte 2.0");
        assertThat(c.backfills()).isTrue();
        assertThat(c.covers(LILLE)).isTrue();
        assertThat(c.covers(new City("paris", "FR", List.of("paris"), List.of(), true))).isFalse();
    }
}
