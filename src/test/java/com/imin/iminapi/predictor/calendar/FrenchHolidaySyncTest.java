package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class FrenchHolidaySyncTest {

    static final String METRO_URL = "https://calendrier.api.gouv.fr/jours-feries/metropole/2027.json";
    static final String AM_URL = "https://calendrier.api.gouv.fr/jours-feries/alsace-moselle/2027.json";
    static final LocalDate TODAY = LocalDate.of(2027, 2, 1);

    @Autowired ReferenceCalendarEntryRepository repository;
    @Autowired ReferenceCalendarWriter writer;
    @Autowired ReferenceCalendarService service;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FrenchHolidaySync sync = new FrenchHolidaySync(builder.build(), CalendarFixtures.props(0));
    private final CalendarRegions regions = new CalendarRegions(OpenDataCities.load());

    /** Both 2027 zones answered with the recorded fixtures. */
    static void expectBoth(MockRestServiceServer server) {
        server.expect(requestTo(METRO_URL)).andRespond(
                withSuccess(CalendarFixtures.text("metropole-2027.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(AM_URL)).andRespond(
                withSuccess(CalendarFixtures.text("alsace-moselle-2027.json"), MediaType.APPLICATION_JSON));
    }

    private List<CalendarSource.Batch> fetch() {
        expectBoth(server);
        List<CalendarSource.Batch> batches = sync.fetch(TODAY);
        server.verify();
        return batches;
    }

    @Test
    void metzGoodFriday2027() {
        fetch().forEach(writer::replace);
        LocalDate goodFriday = LocalDate.of(2027, 3, 26);

        List<CalendarHit> metz = service.on(goodFriday, regions.of("FR", "57000", "Metz"));
        List<CalendarHit> paris = service.on(goodFriday, regions.of("FR", "75011", "Paris"));

        assertThat(metz).singleElement().satisfies(h -> {
            assertThat(h.kind()).isEqualTo("holiday");
            assertThat(h.name()).isEqualTo("Vendredi saint");
            assertThat(h.region()).isEqualTo("FR-57");
            assertThat(h.sourceUrl()).isEqualTo(AM_URL);
            assertThat(h.origin()).isEqualTo(CalendarHit.SYNCED);
        });
        assertThat(paris).isEmpty();
    }

    @Test
    void alsaceOnlyRowsStoredForThreeDepartments() {
        CalendarSource.Batch am = fetch().get(1);

        assertThat(am.sourceUrl()).isEqualTo(AM_URL);
        assertThat(am.kinds()).containsExactlyInAnyOrder("holiday", "pont");
        assertThat(am.from()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(am.to()).isEqualTo(LocalDate.of(2027, 12, 31));
        // Good Friday is a Friday and 26 Dec 2027 a Sunday, so no Alsace-only pont
        assertThat(am.rows()).extracting(CalendarRow::region, CalendarRow::date, CalendarRow::name)
                .containsExactlyInAnyOrder(
                        tuple("FR-57", LocalDate.of(2027, 3, 26), "Vendredi saint"),
                        tuple("FR-67", LocalDate.of(2027, 3, 26), "Vendredi saint"),
                        tuple("FR-68", LocalDate.of(2027, 3, 26), "Vendredi saint"),
                        tuple("FR-57", LocalDate.of(2027, 12, 26), "2ème jour de Noël"),
                        tuple("FR-67", LocalDate.of(2027, 12, 26), "2ème jour de Noël"),
                        tuple("FR-68", LocalDate.of(2027, 12, 26), "2ème jour de Noël"));
    }

    @Test
    void sharedRowsNotDuplicatedIntoRegions() {
        CalendarSource.Batch metro = fetch().get(0);

        assertThat(metro.sourceUrl()).isEqualTo(METRO_URL);
        assertThat(metro.rows()).filteredOn(r -> r.kind().equals("holiday")).hasSize(11)
                .allSatisfy(r -> assertThat(r.region()).isEmpty());
        // Thursdays 6 May (Ascension) and 11 Nov 2027 make the Fridays after them ponts
        assertThat(metro.rows()).filteredOn(r -> r.kind().equals("pont")).containsExactly(
                new CalendarRow("FR", "", LocalDate.of(2027, 5, 7), null, "pont", "pont:Ascension", METRO_URL),
                new CalendarRow("FR", "", LocalDate.of(2027, 11, 12), null, "pont", "pont:11 novembre", METRO_URL));
    }

    @Test
    void tuesdayNewYearMakesThisYearsLastDayAPont() {
        // synthetic 2029 answer: 1 Jan 2030 is a Tuesday, so Monday 31 Dec 2029 is a pont (25 Dec too)
        String metro2029 = "https://calendrier.api.gouv.fr/jours-feries/metropole/2029.json";
        server.expect(requestTo(metro2029)).andRespond(withSuccess(
                "{\"2029-01-01\": \"1er janvier\", \"2029-12-25\": \"Jour de Noël\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://calendrier.api.gouv.fr/jours-feries/alsace-moselle/2029.json"))
                .andRespond(withServerError());

        List<CalendarSource.Batch> batches = sync.fetch(LocalDate.of(2029, 2, 1));

        assertThat(batches).singleElement().satisfies(b -> assertThat(b.rows())
                .filteredOn(r -> r.kind().equals("pont")).containsExactlyInAnyOrder(
                        new CalendarRow("FR", "", LocalDate.of(2029, 12, 24), null, "pont", "pont:Jour de Noël", metro2029),
                        new CalendarRow("FR", "", LocalDate.of(2029, 12, 31), null, "pont", "pont:1er janvier", metro2029)));
        assertThat(batches.get(0).rows()).extracting(CalendarRow::date).doesNotContain(LocalDate.of(2030, 1, 1));
        server.verify();
    }

    @Test
    void metropoleFailureSkipsTheYear() {
        server.expect(requestTo(METRO_URL)).andRespond(withServerError());

        assertThat(sync.fetch(TODAY)).isEmpty();
        server.verify();
    }

    @Test
    void alsaceFailureKeepsTheMetropoleBatch() {
        server.expect(requestTo(METRO_URL)).andRespond(
                withSuccess(CalendarFixtures.text("metropole-2027.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(AM_URL)).andRespond(withServerError());

        assertThat(sync.fetch(TODAY)).extracting(CalendarSource.Batch::sourceUrl).containsExactly(METRO_URL);
    }

    @Test
    void emptyAnswerReturnsNoBatch() {
        server.expect(requestTo(METRO_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(sync.fetch(TODAY)).isEmpty();
    }
}
