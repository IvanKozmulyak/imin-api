package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * data-11: the two backlog scans the predictor's scheduled jobs run were derived queries with a
 * LIMIT and <b>no sort</b>.
 *
 * <p>That is a starvation bug, not a cosmetic one, because neither job finalizes everything it
 * fetches: {@code EventOutcomeFinalizeJob} skips every outcome whose event has not yet ended past
 * the grace window, and {@code PredictionScoringJob} skips every render whose outcome is not
 * finalized. A frozen outcome row is written at publish and stays unfinalized until well after the
 * event, so the unfinalized set is dominated by future events and grows with the published-event
 * count. Once it exceeds the page size, an unordered page can be filled entirely with
 * not-yet-due rows while a genuinely due one is never selected — every tick, indefinitely.
 * EventRepository.findPayoutCandidates and OrderRepository.findDue24hReminder already carry an
 * explicit ORDER BY for exactly this reason.
 *
 * <p>Oldest-first is the order that drains: the rows that have been waiting longest are the ones
 * most likely to be due, and a deterministic tiebreaker on the id keeps the page stable across
 * ticks when the sort key ties.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class PredictorPagedScanOrderTest {

    @Autowired EventOutcomeRepository outcomes;
    @Autowired PredictionLedgerRepository ledger;

    @BeforeEach
    void wipe() {
        outcomes.deleteAll();
        ledger.deleteAll();
    }

    @Test
    void unfinalizedOutcomeScanReturnsTheOldestFrozenRowsFirst() {
        // Inserted newest-first, so insertion order is the OPPOSITE of the order that drains —
        // an unsorted page would hand back the newest and starve the oldest.
        EventOutcome newest = outcome(Instant.parse("2026-03-01T00:00:00Z"));
        EventOutcome middle = outcome(Instant.parse("2026-02-01T00:00:00Z"));
        EventOutcome oldest = outcome(Instant.parse("2026-01-01T00:00:00Z"));
        outcomes.saveAll(List.of(newest, middle, oldest));

        List<EventOutcome> page = outcomes.findByFinalizedAtIsNullOrderByFrozenAtAscEventIdAsc(
                PageRequest.of(0, 2));

        assertThat(page).extracting(EventOutcome::getEventId)
                .containsExactly(oldest.getEventId(), middle.getEventId());
    }

    @Test
    void unjoinedLedgerScanReturnsTheOldestRendersFirst() {
        PredictionLedger newest = ledger.save(render(Instant.parse("2026-03-01T00:00:00Z")));
        PredictionLedger middle = ledger.save(render(Instant.parse("2026-02-01T00:00:00Z")));
        PredictionLedger oldest = ledger.save(render(Instant.parse("2026-01-01T00:00:00Z")));

        List<PredictionLedger> page = ledger.findByOutcomeJoinedAtIsNullOrderByCreatedAtAscIdAsc(
                PageRequest.of(0, 2));

        assertThat(page).extracting(PredictionLedger::getId)
                .containsExactly(oldest.getId(), middle.getId());
    }

    private static EventOutcome outcome(Instant frozenAt) {
        EventOutcome o = new EventOutcome();
        o.setEventId(UUID.randomUUID());
        o.setOrgId(UUID.randomUUID());
        o.setFrozenAt(frozenAt);
        return o;
    }

    private static PredictionLedger render(Instant createdAt) {
        PredictionLedger l = new PredictionLedger();
        l.setEventId(UUID.randomUUID());
        l.setOrgId(UUID.randomUUID());
        l.setSurface(PredictionSurface.PRE_PUBLISH);
        l.setStage((short) 0);
        l.setModelId("test-model");
        l.setPromptVersion("v1");
        l.setInputSnapshotHash("hash");
        l.setCreatedAt(createdAt);
        return l;
    }
}
