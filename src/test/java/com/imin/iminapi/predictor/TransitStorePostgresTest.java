package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.model.TransitDisruption;
import com.imin.iminapi.predictor.model.TransitSyncState;
import com.imin.iminapi.predictor.repository.TransitDisruptionRepository;
import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
import com.imin.iminapi.predictor.sources.SourceSyncDates;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Disruption;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.LineRef;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Period;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Snapshot;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Status;
import com.imin.iminapi.predictor.sources.prim.IdfmStopsClient.Stop;
import com.imin.iminapi.predictor.sources.prim.PrimWriter;
import com.imin.iminapi.predictor.sources.prim.TransitStopStore;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** The PRIM and stops stores on Postgres: whole replace, keep-on-failure, the overlap and radius queries, source dates. */
@IminIntegrationTest
class TransitStorePostgresTest {

    private static final Instant T1 = Instant.parse("2026-10-07T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-07T10:30:00Z");

    @Autowired PrimWriter writer;
    @Autowired TransitDisruptionRepository disruptions;
    @Autowired TransitSyncStateRepository states;
    @Autowired SourceSyncDates syncDates;
    @Autowired TransitStopStore stops;
    @Autowired JdbcTemplate jdbc;

    private static final double VENUE_LAT = 48.8606;
    private static final double VENUE_LNG = 2.3376;

    private static String id(String tag) {
        return tag + "-" + UUID.randomUUID();
    }

    private static Disruption disruption(String id, Instant begin, Instant end) {
        return new Disruption(id, "TRAVAUX", "BLOQUANTE", "works", "RER B : Travaux", T1,
                List.of(new LineRef("line:IDFM:C01743", "RER B", "RapidTransit", "line", null)), List.of(new Period(begin, end)));
    }

    private static Disruption disruption(String id) {
        return disruption(id, Instant.parse("2026-10-10T21:00:00Z"), Instant.parse("2026-10-10T23:00:00Z"));
    }

    /** A stop ref no real IDFM stop uses. */
    private static String stopRef() {
        return "IDFM:9" + ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999);
    }

    /** A point {@code m} metres due east of the venue on the mean-radius sphere. */
    private static Stop east(String ref, double m) {
        double dLng = Math.toDegrees(2 * Math.asin(Math.sin(m / (2 * 6_371_008.8)) / Math.cos(Math.toRadians(VENUE_LAT))));
        return new Stop(ref, VENUE_LAT, VENUE_LNG + dLng);
    }

    private List<String> storedStops() {
        return jdbc.queryForList("SELECT stop_ref FROM transit_stop WHERE source = 'idfm-stops'", String.class);
    }

    private List<String> storedIds() {
        return disruptions.findAll().stream().filter(d -> d.getSource().equals("idfm-prim"))
                .map(TransitDisruption::getDisruptionId).toList();
    }

    @Test
    void replaceSwapsAllRowsAndStampsState() {
        String a = id("a"), b = id("b"), c = id("c");
        writer.replace(new Snapshot(T1, List.of(disruption(a), disruption(b)), 0), T1);
        Instant feed = Instant.parse("2026-10-07T10:28:00Z");

        writer.replace(new Snapshot(feed, List.of(disruption(c)), 0), T2);

        assertThat(storedIds()).containsExactly(c);
        TransitSyncState s = states.findById("idfm-prim").orElseThrow();
        assertThat(s.getLastStatus()).isEqualTo("ok");
        assertThat(s.getSyncedAt()).isEqualTo(T2);
        assertThat(s.getFeedUpdatedAt()).isEqualTo(feed);
        assertThat(s.getDisruptionCount()).isEqualTo(1);
        assertThat(s.getLastAttemptAt()).isEqualTo(T2);
    }

    @Test
    void recordAttemptKeepsRowsAndSyncedAt() {
        String a = id("a");
        writer.replace(new Snapshot(T1, List.of(disruption(a)), 0), T1);

        writer.recordAttempt(Status.RATE_LIMITED, T2);

        assertThat(storedIds()).containsExactly(a);
        TransitSyncState s = states.findById("idfm-prim").orElseThrow();
        assertThat(s.getSyncedAt()).isEqualTo(T1);
        assertThat(s.getLastStatus()).isEqualTo("rate_limited");
        assertThat(s.getLastAttemptAt()).isEqualTo(T2);
    }

    @Test
    void findOverlappingReturnsOnlyOverlapping() {
        Instant from = Instant.parse("2026-10-10T20:00:00Z");
        Instant to = Instant.parse("2026-10-11T00:00:00Z");
        String endsAtFrom = id("ends-at-from"), beginsAtTo = id("begins-at-to"), inside = id("inside");
        // the excluded rows are written first, so a query that leaks them would return them first
        writer.replace(new Snapshot(T1, List.of(
                disruption(endsAtFrom, from.minusSeconds(3600), from),
                disruption(beginsAtTo, to, to.plusSeconds(3600)),
                disruption(inside, from.minusSeconds(60), from.plusSeconds(60))), 0), T1);

        assertThat(disruptions.findOverlapping("idfm-prim", from, to)).extracting(TransitDisruption::getDisruptionId)
                .containsExactly(inside);
    }

    @Test
    void lastUpdatedIsUtcDateOfStateSyncedAt() {
        // 23:30Z is already 8 October in Paris; an ok poll with no rows still dates the source
        writer.replace(new Snapshot(null, List.of(), 0), Instant.parse("2026-10-07T23:30:00Z"));

        assertThat(syncDates.lastUpdatedOfSource("idfm-prim")).contains(LocalDate.of(2026, 10, 7));
    }

    @Test
    void stopsReplaceSwapsAndStampsState() {
        writer.replace(new Snapshot(T1, List.of(), 0), T1);
        String a = stopRef(), b = stopRef(), c = stopRef();
        stops.replace(List.of(east(a, 100), east(b, 200)), T1);

        stops.replace(List.of(east(c, 300)), T2);

        assertThat(storedStops()).containsExactly(c);
        TransitSyncState s = states.findById("idfm-stops").orElseThrow();
        assertThat(s.getLastStatus()).isEqualTo("ok");
        assertThat(s.getSyncedAt()).isEqualTo(T2);
        assertThat(s.getLastAttemptAt()).isEqualTo(T2);
        TransitSyncState prim = states.findById("idfm-prim").orElseThrow();
        assertThat(prim.getSyncedAt()).isEqualTo(T1);
        assertThat(prim.getLastAttemptAt()).isEqualTo(T1);
    }

    @Test
    void nearbyReturnsOnlyInsideRadius() {
        String out = stopRef(), far = stopRef(), corner = stopRef(), in = stopRef();
        // 600 m north and 600 m east is about 849 m: inside the box, so only the distance filter drops it
        Stop ne = east(corner, 600);
        ne = new Stop(corner, VENUE_LAT + Math.toDegrees(600 / 6_371_008.8), ne.lng());
        // the excluded stops are written first, so a query that leaks them would return them first
        stops.replace(List.of(east(out, 801), new Stop(far, 48.9, 2.5), ne, east(in, 799)), T1);

        assertThat(stops.nearby(VENUE_LAT, VENUE_LNG, 800)).containsExactly(in);
    }

    @Test
    void stopsLastUpdatedIsUtcDateOfState() {
        // 23:30Z is already 8 October in Paris
        stops.replace(List.of(east(stopRef(), 10)), Instant.parse("2026-10-07T23:30:00Z"));

        assertThat(syncDates.lastUpdatedOfSource("idfm-stops")).contains(LocalDate.of(2026, 10, 7));
    }

    @Test
    void recordAttemptKeepsStops() {
        String a = stopRef();
        stops.replace(List.of(east(a, 10)), T1);

        stops.recordAttempt(Status.FAILED, T2);

        assertThat(storedStops()).containsExactly(a);
        TransitSyncState s = states.findById("idfm-stops").orElseThrow();
        assertThat(s.getSyncedAt()).isEqualTo(T1);
        assertThat(s.getLastStatus()).isEqualTo("failed");
        assertThat(s.getLastAttemptAt()).isEqualTo(T2);
    }
}
