package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.model.FeedbackType;
import com.imin.iminapi.predictor.model.PredictionFeedback;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.repository.PredictionFeedbackRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.PredictionLedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3 (prediction ledger) — the write-before-render record, the outcome join, and the
 * recommendation feedback write. Spec §5, §7.2, §4.3. The ledger is app-scoped (no FK), so
 * these use random ids without seeding events.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class PredictionLedgerServiceTest {

    @Autowired PredictionLedgerService service;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired PredictionFeedbackRepository feedback;

    @BeforeEach
    void setUp() { wipe(); }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        feedback.deleteAll();
        ledger.deleteAll();
    }

    private PredictionLedgerService.RecordCommand cmd(UUID eventId, UUID orgId) {
        return new PredictionLedgerService.RecordCommand(
                eventId, orgId, PredictionSurface.PRE_PUBLISH, 0,
                "anthropic/claude-sonnet-4.6", "1.0.0", "hash-abc123",
                "{\"ids\":[],\"clusterSize\":0,\"relaxation\":\"NONE\"}",
                "{\"sellOutBand\":\"55-75\"}");
    }

    @Test
    void record_persistsRow_andReturnsId() {
        UUID eventId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        UUID id = service.record(cmd(eventId, orgId));

        assertThat(id).isNotNull();
        PredictionLedger row = ledger.findById(id).orElseThrow();
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getOrgId()).isEqualTo(orgId);
        assertThat(row.getSurface()).isEqualTo(PredictionSurface.PRE_PUBLISH);
        assertThat(row.getStage()).isEqualTo((short) 0);
        assertThat(row.getModelId()).isEqualTo("anthropic/claude-sonnet-4.6");
        assertThat(row.getPromptVersion()).isEqualTo("1.0.0");
        assertThat(row.getInputSnapshotHash()).isEqualTo("hash-abc123");
        assertThat(row.getComparablesJson()).contains("clusterSize");
        assertThat(row.getOutputJson()).contains("sellOutBand");
        assertThat(row.getCreatedAt()).isNotNull();
        // outcome-join columns are null until the scoring job runs
        assertThat(row.getActualSold()).isNull();
        assertThat(row.getOutcomeJoinedAt()).isNull();
    }

    @Test
    void record_defaultsNullJsonToEmptyObject() {
        UUID id = service.record(new PredictionLedgerService.RecordCommand(
                UUID.randomUUID(), UUID.randomUUID(), PredictionSurface.REFORECAST, 1,
                "pacing-engine", "0.1.0", "hash-x", null, null));
        PredictionLedger row = ledger.findById(id).orElseThrow();
        assertThat(row.getComparablesJson()).isEqualTo("{}");
        assertThat(row.getOutputJson()).isEqualTo("{}");
        assertThat(row.getStage()).isEqualTo((short) 1);
    }

    @Test
    void joinOutcome_fillsOutcomeColumns() {
        UUID id = service.record(cmd(UUID.randomUUID(), UUID.randomUUID()));
        Instant when = Instant.parse("2026-04-01T05:00:00Z");

        service.joinOutcome(id, 240, 198, when, new BigDecimal("0.040000"), new BigDecimal("0.175000"));

        PredictionLedger row = ledger.findById(id).orElseThrow();
        assertThat(row.getActualSold()).isEqualTo(240);
        assertThat(row.getActualAttendance()).isEqualTo(198);
        // Stamped (exact-instant round-trip depends on the test profile's JDBC timezone, which
        // is UTC in prod but unset under H2 — assert presence, not the offset-sensitive value).
        assertThat(row.getOutcomeJoinedAt()).isNotNull();
        assertThat(row.getBrierComponent()).isEqualByComparingTo("0.040000");
        assertThat(row.getApe()).isEqualByComparingTo("0.175000");
        // the row drops out of the scoring-job candidate query
        assertThat(ledger.findByOutcomeJoinedAtIsNull(org.springframework.data.domain.PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void recordFeedback_persistsDismissalVisibleInLedger() {
        UUID eventId = UUID.randomUUID();
        UUID ledgerId = service.record(cmd(eventId, UUID.randomUUID()));

        UUID fbId = service.recordFeedback(ledgerId, eventId, "rec-tier-price-1", FeedbackType.DISMISSED);

        assertThat(fbId).isNotNull();
        List<PredictionFeedback> byLedger = feedback.findByLedgerId(ledgerId);
        assertThat(byLedger).hasSize(1);
        PredictionFeedback fb = byLedger.get(0);
        assertThat(fb.getEventId()).isEqualTo(eventId);
        assertThat(fb.getRecommendationId()).isEqualTo("rec-tier-price-1");
        assertThat(fb.getFeedbackType()).isEqualTo(FeedbackType.DISMISSED);

        // executions are logged too, independently, on the same render
        service.recordFeedback(ledgerId, eventId, "rec-add-tier-2", FeedbackType.EXECUTED);
        assertThat(feedback.findByEventId(eventId)).hasSize(2);
        assertThat(feedback.findByEventIdAndRecommendationId(eventId, "rec-add-tier-2")).hasSize(1);
    }
}
