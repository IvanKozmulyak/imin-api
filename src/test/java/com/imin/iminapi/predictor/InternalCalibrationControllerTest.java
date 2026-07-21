package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.PredictorSegmentStatus;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorSegmentStatusRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Founders-only calibration view (task 86cav476q): blank token keeps the endpoint DARK
 * (404), wrong token is indistinguishable (404), correct token renders the instrument
 * panel from ledger + outcome + segment data alone.
 */
class InternalCalibrationControllerTest {

    private static final String PATH = "/api/v1/internal/predictor/calibration";

    /** Default config: token blank → the endpoint does not exist for anyone. */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(TestRateLimitConfig.class)
    class TokenBlank {
        @Autowired MockMvc mvc;

        @Test
        void endpointIsDarkWithoutConfiguredToken() throws Exception {
            mvc.perform(get(PATH)).andExpect(status().isNotFound());
            mvc.perform(get(PATH).header("Authorization", "Bearer anything"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @SpringBootTest(properties = "imin.predictor.internal-token=founders-secret-token")
    @AutoConfigureMockMvc
    @Import(TestRateLimitConfig.class)
    class TokenConfigured {
        @Autowired MockMvc mvc;
        @Autowired PredictionLedgerRepository ledger;
        @Autowired EventOutcomeRepository outcomes;
        @Autowired PredictorSegmentStatusRepository segments;

        @AfterEach
        void clean() {
            ledger.deleteAll();
            outcomes.deleteAll();
            segments.deleteAll();
        }

        @Test
        void wrongOrMissingTokenIs404() throws Exception {
            mvc.perform(get(PATH)).andExpect(status().isNotFound());
            mvc.perform(get(PATH).header("Authorization", "Bearer wrong"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void correctTokenRendersPanelFromSeededLedger() throws Exception {
            // One scored render: predicted 40-60% sell-out, realized sell-out, attendance inside band.
            UUID eventId = UUID.randomUUID();
            PredictionLedger row = new PredictionLedger();
            row.setEventId(eventId);
            row.setOrgId(UUID.randomUUID());
            row.setSurface(PredictionSurface.PRE_PUBLISH);
            row.setStage((short) 0);
            row.setModelId("test/model");
            row.setPromptVersion("1.0.0");
            row.setInputSnapshotHash("h".repeat(64));
            row.setOutputJson("{\"surface\":\"pre_publish\",\"stage\":0,\"confidenceTier\":\"B\","
                    + "\"selloutBand\":{\"lowPct\":40,\"highPct\":60},"
                    + "\"attendanceRange\":{\"low\":150,\"high\":220},"
                    + "\"benchmarkOnly\":false}");
            row.setOutcomeJoinedAt(Instant.now());
            row.setActualAttendance(200);
            row.setApe(new BigDecimal("0.075000"));
            ledger.save(row);

            EventOutcome o = new EventOutcome();
            o.setEventId(eventId);
            o.setOrgId(row.getOrgId());
            o.setGenreFamily("techno");
            o.setCapacityBand(CapacityBand.B101_300);
            o.setSellOut(true);
            o.setAttendance(200);
            o.setGrossRevenueMinor(400_000L);
            o.setFinalizedAt(Instant.now());
            outcomes.save(o);

            PredictorSegmentStatus seg = new PredictorSegmentStatus();
            seg.setSegmentKey("techno|B101_300");
            seg.setScoredCount(1);
            seg.setBrier(new BigDecimal("0.250000"));
            seg.setBaseRateBrier(new BigDecimal("0.000000"));
            seg.setLanguageTierOverride(PredictorSegmentStatus.OVERRIDE_DROP_ONE);
            seg.setReason("Brier 0.25 not beating base-rate 0.0 over 21 scored renders");
            segments.save(seg);

            mvc.perform(get(PATH).header("Authorization", "Bearer founders-secret-token"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("calibration")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("techno|B101_300")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("DROP_ONE")))
                    // the 40-60% bucket has 1 render with a realized sell-out
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("40–60%")));
        }
    }
}
