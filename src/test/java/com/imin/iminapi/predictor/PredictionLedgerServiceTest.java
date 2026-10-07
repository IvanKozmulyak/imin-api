package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.model.FeedbackType;
import com.imin.iminapi.predictor.model.PredictionFeedback;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.repository.PredictionFeedbackRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.PredictionLedgerService;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PredictorRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 3 (prediction ledger) — the outcome join and the recommendation feedback write. Spec §5, §7.2, §4.3.
 * The ledger is app-scoped (no FK), so these use random ids without seeding events and delete their own rows.
 */
@IminIntegrationTest
class PredictionLedgerServiceTest {

    @Autowired PredictionLedgerService service;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired PredictionFeedbackRepository feedback;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

    /** An unjoined row would otherwise wait in the scoring job's candidate queue for every later test. */
    @AfterEach
    void tearDown() {
        for (UUID eventId : eventIds) jdbc.update("DELETE FROM prediction_feedback WHERE event_id = ?", eventId);
        PredictorRows.delete(jdbc, orgIds);
    }

    private PredictionLedgerService.RecordCommand cmd(UUID eventId) {
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);
        eventIds.add(eventId);
        return new PredictionLedgerService.RecordCommand(
                eventId, orgId, PredictionSurface.PRE_PUBLISH, 0,
                "anthropic/claude-sonnet-4.6", "1.0.0", "hash-abc123",
                "{\"ids\":[],\"clusterSize\":0,\"relaxation\":\"NONE\"}",
                "{\"sellOutBand\":\"55-75\"}");
    }

    @Test
    void joinOutcome_fillsOutcomeColumns() {
        UUID id = service.record(cmd(UUID.randomUUID()));
        Instant when = Instant.parse("2026-04-01T05:00:00Z");
        assertThat(candidateIds()).contains(id);

        service.joinOutcome(id, 240, 198, when, new BigDecimal("0.040000"), new BigDecimal("0.175000"));

        PredictionLedger row = ledger.findById(id).orElseThrow();
        assertThat(row.getActualSold()).isEqualTo(240);
        assertThat(row.getActualAttendance()).isEqualTo(198);
        assertThat(row.getOutcomeJoinedAt()).isEqualTo(when);
        assertThat(row.getBrierComponent()).isEqualByComparingTo("0.040000");
        assertThat(row.getApe()).isEqualByComparingTo("0.175000");
        // the row drops out of the scoring-job candidate query
        assertThat(candidateIds()).doesNotContain(id);
    }

    /** Every org's unjoined rows are candidates, so the page is wide enough to hold them all. */
    private List<UUID> candidateIds() {
        List<PredictionLedger> page = ledger.findByOutcomeJoinedAtIsNullOrderByCreatedAtAscIdAsc(PageRequest.of(0, 10_000));
        assertThat(page).hasSizeLessThan(10_000);
        return page.stream().map(PredictionLedger::getId).toList();
    }

    @Test
    void recordFeedback_persistsDismissalVisibleInLedger() {
        UUID eventId = UUID.randomUUID();
        UUID ledgerId = service.record(cmd(eventId));

        UUID fbId = service.recordFeedback(ledgerId, eventId, "rec-tier-price-1", FeedbackType.DISMISSED);

        assertThat(fbId).isNotNull();
        List<PredictionFeedback> byLedger = feedback.findByEventId(eventId).stream()
                .filter(f -> ledgerId.equals(f.getLedgerId()))
                .toList();
        assertThat(byLedger).hasSize(1);
        PredictionFeedback fb = byLedger.get(0);
        assertThat(fb.getEventId()).isEqualTo(eventId);
        assertThat(fb.getRecommendationId()).isEqualTo("rec-tier-price-1");
        assertThat(fb.getFeedbackType()).isEqualTo(FeedbackType.DISMISSED);

        // executions are logged too, independently, on the same render
        service.recordFeedback(ledgerId, eventId, "rec-add-tier-2", FeedbackType.EXECUTED);
        assertThat(feedback.findByEventId(eventId)).hasSize(2);
        assertThat(feedback.findByEventId(eventId).stream()
                .filter(f -> "rec-add-tier-2".equals(f.getRecommendationId()))
                .toList()).hasSize(1);
    }

    @Test
    void recordFeedbackRejectsDateVerdictMatch() {
        UUID eventId = UUID.randomUUID();
        UUID ledgerId = service.record(cmd(eventId));

        assertThatThrownBy(() -> service.recordFeedback(ledgerId, eventId, "rec-1", FeedbackType.DATE_VERDICT_MATCH,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordFeedback(ledgerId, eventId, "rec-1", FeedbackType.DATE_VERDICT_MATCH))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(feedback.findByEventId(eventId)).isEmpty();
    }
}
