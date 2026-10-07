package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import jakarta.persistence.EntityManager;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** One transaction per test; writers are built by hand so each run can have its own clock. */
@IminIntegrationTest
@Transactional
class ReferenceCalendarWriterTest {

    private static final Instant T1 = Instant.parse("2026-09-27T02:30:00Z");
    private static final Instant T2 = Instant.parse("2026-10-04T02:30:00Z");
    private static final String URL = "https://example.test/holidays/2027.json";
    private static final String OTHER_URL = "https://other.test";
    private static final LocalDate FROM = LocalDate.of(2027, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 12, 31);

    @Autowired ReferenceCalendarEntryRepository repository;
    @Autowired EntityManager em;

    private ReferenceCalendarWriter writer(Instant at) {
        return new ReferenceCalendarWriter(repository, Clock.fixed(at, ZoneOffset.UTC));
    }

    private static CalendarRow row(String date, String name) {
        return new CalendarRow("FR", "", LocalDate.parse(date), null, "holiday", name, URL);
    }

    private static CalendarSource.Batch batch(CalendarRow... rows) {
        return new CalendarSource.Batch(URL, Set.of("holiday", "pont"), FROM, TO, List.of(rows));
    }

    /** The rows under the source URLs this class writes; the table is shared reference data. */
    private List<ReferenceCalendarEntry> stored() {
        em.flush();
        em.clear();
        Set<String> own = Set.of(URL, OTHER_URL, FrenchHolidaySyncTest.METRO_URL, FrenchHolidaySyncTest.AM_URL);
        return repository.findAll().stream().filter(e -> own.contains(e.getSourceUrl()))
                .sorted(Comparator.comparing(ReferenceCalendarEntry::getCalendarDate)).toList();
    }

    private static Map<String, UUID> ids(List<ReferenceCalendarEntry> rows) {
        return rows.stream().collect(Collectors.toMap(
                e -> e.getRegion() + "|" + e.getCalendarDate() + "|" + e.getKind() + "|" + e.getName(),
                ReferenceCalendarEntry::getId));
    }

    @Test
    void syncIsIdempotent() {
        CalendarSource.Batch b = batch(row("2027-01-01", "1er janvier"), row("2027-05-01", "1er mai"));
        assertThat(writer(T1).replace(b)).isEqualTo(2);
        List<ReferenceCalendarEntry> first = stored();
        Map<String, UUID> firstIds = ids(first);
        assertThat(first).allSatisfy(e -> assertThat(e.getSyncedAt()).isEqualTo(T1));

        assertThat(writer(T2).replace(b)).isEqualTo(2);
        List<ReferenceCalendarEntry> second = stored();

        assertThat(second).hasSize(2);
        assertThat(ids(second)).isEqualTo(firstIds);
        assertThat(second).allSatisfy(e -> assertThat(e.getSyncedAt()).isEqualTo(T2));
    }

    @Test
    void existingRowTakesTheNewEndDate() {
        writer(T1).replace(batch(row("2027-05-01", "1er mai")));

        writer(T2).replace(batch(new CalendarRow("FR", "", LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 2),
                "holiday", "1er mai", URL)));

        assertThat(stored()).singleElement().satisfies(e -> assertThat(e.getEndDate()).isEqualTo(LocalDate.of(2027, 5, 2)));
    }

    @Test
    void upstream500KeepsPreviousRows() {
        RestClient.Builder ok = RestClient.builder();
        MockRestServiceServer okServer = MockRestServiceServer.bindTo(ok).build();
        FrenchHolidaySyncTest.expectBoth(okServer);
        new FrenchHolidaySync(ok.build(), CalendarFixtures.props(0))
                .fetch(FrenchHolidaySyncTest.TODAY).forEach(writer(T1)::replace);
        List<ReferenceCalendarEntry> before = stored();
        Map<String, UUID> beforeIds = ids(before);

        RestClient.Builder failing = RestClient.builder();
        MockRestServiceServer failingServer = MockRestServiceServer.bindTo(failing).build();
        failingServer.expect(requestTo(FrenchHolidaySyncTest.METRO_URL)).andRespond(withServerError());
        new FrenchHolidaySync(failing.build(), CalendarFixtures.props(0))
                .fetch(FrenchHolidaySyncTest.TODAY).forEach(writer(T2)::replace);
        failingServer.verify();

        List<ReferenceCalendarEntry> after = stored();
        assertThat(before).hasSize(11 + 2 + 6);
        assertThat(ids(after)).isEqualTo(beforeIds);
        assertThat(after).allSatisfy(e -> assertThat(e.getSyncedAt()).isEqualTo(T1));
    }

    @Test
    void emptyResponseKeepsPreviousRows() {
        writer(T1).replace(batch(row("2027-01-01", "1er janvier")));

        assertThat(writer(T2).replace(batch())).isZero();

        assertThat(stored()).singleElement().satisfies(e -> assertThat(e.getSyncedAt()).isEqualTo(T1));
    }

    @Test
    void rowDroppedUpstreamIsDeleted() {
        writer(T1).replace(batch(row("2027-01-01", "1er janvier"), row("2027-05-01", "1er mai")));

        writer(T2).replace(batch(row("2027-01-01", "1er janvier")));

        assertThat(stored()).extracting(ReferenceCalendarEntry::getName).containsExactly("1er janvier");
    }

    @Test
    void otherScopesUntouched() {
        CalendarRow otherSource = new CalendarRow("FR", "", LocalDate.of(2027, 3, 1), null, "holiday", "other", OTHER_URL);
        CalendarRow otherKind = new CalendarRow("FR", "", LocalDate.of(2027, 3, 2), null, "dst", "dst_forward", URL);
        CalendarRow otherYear = new CalendarRow("FR", "", LocalDate.of(2028, 1, 1), null, "holiday", "1er janvier", URL);
        writer(T1).replace(new CalendarSource.Batch(OTHER_URL, Set.of("holiday"), FROM, TO, List.of(otherSource)));
        writer(T1).replace(new CalendarSource.Batch(URL, Set.of("dst"), FROM, TO, List.of(otherKind)));
        writer(T1).replace(new CalendarSource.Batch(URL, Set.of("holiday"), LocalDate.of(2028, 1, 1),
                LocalDate.of(2028, 12, 31), List.of(otherYear)));
        writer(T1).replace(batch(row("2027-05-01", "1er mai")));

        writer(T2).replace(batch(row("2027-01-01", "1er janvier")));

        Map<String, ReferenceCalendarEntry> byName = stored().stream()
                .collect(Collectors.toMap(e -> e.getName() + e.getCalendarDate(), Function.identity()));
        assertThat(byName).containsOnlyKeys("other2027-03-01", "dst_forward2027-03-02", "1er janvier2028-01-01",
                "1er janvier2027-01-01");
    }
}
