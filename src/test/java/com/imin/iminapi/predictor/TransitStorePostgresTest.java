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
import com.imin.iminapi.predictor.sources.prim.PrimWriter;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The PRIM store on Postgres: whole-snapshot replace, keep-on-failure, the overlap query and the source date. */
@IminIntegrationTest
class TransitStorePostgresTest {

    private static final Instant T1 = Instant.parse("2026-10-07T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-07T10:30:00Z");

    @Autowired PrimWriter writer;
    @Autowired TransitDisruptionRepository disruptions;
    @Autowired TransitSyncStateRepository states;
    @Autowired SourceSyncDates syncDates;

    private static String id(String tag) {
        return tag + "-" + UUID.randomUUID();
    }

    private static Disruption disruption(String id, Instant begin, Instant end) {
        return new Disruption(id, "TRAVAUX", "BLOQUANTE", "works", "RER B : Travaux", T1,
                List.of(new LineRef("line:IDFM:C01743", "RER B", "RapidTransit", "line")), List.of(new Period(begin, end)));
    }

    private static Disruption disruption(String id) {
        return disruption(id, Instant.parse("2026-10-10T21:00:00Z"), Instant.parse("2026-10-10T23:00:00Z"));
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
}
