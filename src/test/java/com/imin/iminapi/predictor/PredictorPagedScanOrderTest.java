package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PredictorRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
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
 *
 * <p>The scans read every org's rows, so each test reads one page wide enough for all of them and asserts
 * the order of its own rows within it.
 */
@IminIntegrationTest
class PredictorPagedScanOrderTest {

    private static final PageRequest ALL = PageRequest.of(0, 10_000);

    @Autowired EventOutcomeRepository outcomes;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        PredictorRows.delete(jdbc, orgIds);
    }

    private static <T> List<T> wholePage(List<T> page) {
        assertThat(page).hasSizeLessThan(ALL.getPageSize());
        return page;
    }

    @Test
    void unfinalizedOutcomeScanReturnsTheOldestFrozenRowsFirst() {
        // Inserted newest-first, so insertion order is the OPPOSITE of the order that drains —
        // an unsorted page would hand back the newest and starve the oldest.
        EventOutcome newest = outcome(Instant.parse("2026-03-01T00:00:00Z"));
        EventOutcome middle = outcome(Instant.parse("2026-02-01T00:00:00Z"));
        EventOutcome oldest = outcome(Instant.parse("2026-01-01T00:00:00Z"));
        outcomes.saveAll(List.of(newest, middle, oldest));

        List<UUID> mine = List.of(newest.getEventId(), middle.getEventId(), oldest.getEventId());
        List<EventOutcome> page = wholePage(outcomes.findByFinalizedAtIsNullOrderByFrozenAtAscEventIdAsc(ALL));

        assertThat(page).extracting(EventOutcome::getEventId).filteredOn(mine::contains)
                .containsExactly(oldest.getEventId(), middle.getEventId(), newest.getEventId());
    }

    @Test
    void unjoinedLedgerScanReturnsTheOldestRendersFirst() {
        PredictionLedger newest = ledger.save(render(Instant.parse("2026-03-01T00:00:00Z")));
        PredictionLedger middle = ledger.save(render(Instant.parse("2026-02-01T00:00:00Z")));
        PredictionLedger oldest = ledger.save(render(Instant.parse("2026-01-01T00:00:00Z")));

        List<UUID> mine = List.of(newest.getId(), middle.getId(), oldest.getId());
        List<PredictionLedger> page = wholePage(ledger.findByOutcomeJoinedAtIsNullOrderByCreatedAtAscIdAsc(ALL));

        assertThat(page).extracting(PredictionLedger::getId).filteredOn(mine::contains)
                .containsExactly(oldest.getId(), middle.getId(), newest.getId());
    }

    @Test
    void joinableScanExcludesRowsWithoutEvent() {
        EventOutcome finalized = outcome(Instant.parse("2026-01-01T00:00:00Z"));
        finalized.setFinalizedAt(Instant.parse("2026-02-01T00:00:00Z"));
        outcomes.save(finalized);
        PredictionLedger eventRender = render(Instant.parse("2026-01-02T00:00:00Z"));
        eventRender.setEventId(finalized.getEventId());
        eventRender = ledger.save(eventRender);
        PredictionLedger dateCheckRender = render(Instant.parse("2026-01-01T00:00:00Z"));
        dateCheckRender.setEventId(null);
        dateCheckRender.setSurface(PredictionSurface.DATE_CHECK);
        dateCheckRender.setDateCheckId(UUID.randomUUID());
        dateCheckRender = ledger.save(dateCheckRender);

        List<UUID> mine = List.of(eventRender.getId(), dateCheckRender.getId());
        List<PredictionLedger> page = wholePage(ledger.findJoinable(ALL));

        assertThat(page).extracting(PredictionLedger::getId).filteredOn(mine::contains)
                .containsExactly(eventRender.getId());
    }

    @Test
    void findJoinableSkipsDateCheckRowEvenWithFinalizedEvent() {
        EventOutcome finalized = outcome(Instant.parse("2026-01-01T00:00:00Z"));
        finalized.setFinalizedAt(Instant.parse("2026-02-01T00:00:00Z"));
        outcomes.save(finalized);
        PredictionLedger eventRender = render(Instant.parse("2026-01-02T00:00:00Z"));
        eventRender.setEventId(finalized.getEventId());
        eventRender = ledger.save(eventRender);
        // Only an event-linked DATE_CHECK row gets past the exists clause, so only it reaches the surface filter.
        PredictionLedger dateCheckRender = render(Instant.parse("2026-01-01T00:00:00Z"));
        dateCheckRender.setEventId(finalized.getEventId());
        dateCheckRender.setSurface(PredictionSurface.DATE_CHECK);
        dateCheckRender.setDateCheckId(UUID.randomUUID());
        dateCheckRender = ledger.save(dateCheckRender);

        List<UUID> mine = List.of(eventRender.getId(), dateCheckRender.getId());
        List<PredictionLedger> page = wholePage(ledger.findJoinable(ALL));

        assertThat(page).extracting(PredictionLedger::getId).filteredOn(mine::contains)
                .containsExactly(eventRender.getId());
    }

    private EventOutcome outcome(Instant frozenAt) {
        EventOutcome o = new EventOutcome();
        o.setEventId(UUID.randomUUID());
        o.setOrgId(orgId());
        o.setFrozenAt(frozenAt);
        return o;
    }

    private UUID orgId() {
        UUID id = UUID.randomUUID();
        orgIds.add(id);
        return id;
    }

    private PredictionLedger render(Instant createdAt) {
        PredictionLedger l = new PredictionLedger();
        l.setEventId(UUID.randomUUID());
        l.setOrgId(orgId());
        l.setSurface(PredictionSurface.PRE_PUBLISH);
        l.setStage((short) 0);
        l.setModelId("test-model");
        l.setPromptVersion("v1");
        l.setInputSnapshotHash("hash");
        l.setCreatedAt(createdAt);
        return l;
    }
}
