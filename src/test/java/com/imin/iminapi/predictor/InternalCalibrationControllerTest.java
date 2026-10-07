package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.PredictorSegmentStatus;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorSegmentStatusRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PredictorRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Founders-only calibration view: a blank token keeps the endpoint dark (404), a wrong token is indistinguishable
 * (404), the correct token renders the instrument panel from ledger + outcome + segment data alone.
 */
@IminIntegrationTest
class InternalCalibrationControllerTest {

    private static final String PATH = "/api/v1/internal/predictor/calibration";
    private static final String TOKEN = "t0k";

    @Autowired MockMvc mvc;
    @Autowired PropertyFlips flips;
    @Autowired PredictorProperties props;
    @Autowired JdbcTemplate jdbc;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired EventOutcomeRepository outcomes;
    @Autowired PredictorSegmentStatusRepository segments;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<String> segmentIds = new ArrayList<>();
    /** Segment keys are the primary key, so this test owns a key no other test writes. */
    private final String segmentKey = "techno" + DateCheckControllerTest.letters() + "|B101_300";

    @AfterEach
    void clean() {
        try {
            segments.deleteAllById(segmentIds);
        } finally {
            PredictorRows.delete(jdbc, orgIds);
        }
    }

    /** Default config: token blank, so the endpoint does not exist for anyone. */
    @Test
    void endpointIsDarkWithoutConfiguredToken() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isNotFound());
        mvc.perform(get(PATH).header("Authorization", "Bearer anything"))
                .andExpect(status().isNotFound());
        // A blank bearer would equal the blank secret if the dark check were missing.
        mvc.perform(get(PATH).header("Authorization", "Bearer "))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongOrMissingTokenIs404() throws Exception {
        flips.set(props, "internalToken", TOKEN);

        mvc.perform(get(PATH)).andExpect(status().isNotFound());
        mvc.perform(get(PATH).header("Authorization", "Bearer wrong"))
                .andExpect(status().isNotFound());
    }

    @Test
    void correctTokenRendersPanelFromSeededLedger() throws Exception {
        flips.set(props, "internalToken", TOKEN);
        // One scored render: predicted 40-60% sell-out, realized sell-out, attendance inside band.
        UUID eventId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);
        PredictionLedger row = new PredictionLedger();
        row.setEventId(eventId);
        row.setOrgId(orgId);
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
        seg.setSegmentKey(segmentKey);
        seg.setScoredCount(1);
        seg.setBrier(new BigDecimal("0.250000"));
        seg.setBaseRateBrier(new BigDecimal("0.000000"));
        seg.setLanguageTierOverride(PredictorSegmentStatus.OVERRIDE_DROP_ONE);
        seg.setReason("Brier 0.25 not beating base-rate 0.0 over 21 scored renders");
        segmentIds.add(segments.save(seg).getSegmentKey());

        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("calibration")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(segmentKey)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DROP_ONE")))
                // the 40-60% bucket has 1 render with a realized sell-out
                .andExpect(content().string(org.hamcrest.Matchers.containsString("40–60%")));
    }
}
