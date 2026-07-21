package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Monthly scoring job SCAFFOLD (spec §5 downgrade tripwires, §7.3 evaluation). Once a month
 * it joins completed-event outcomes back onto their ledger renders — filling
 * {@code actual_sold} / {@code actual_attendance} / {@code outcome_joined_at} so the ledger
 * becomes a scored evaluation set.
 *
 * <p>The scoring MATH is deliberately minimal here (brier_component / ape left null): the real
 * Brier / MAPE / calibration formulas and the automatic language-tier downgrades arrive with
 * the Score phase. What ships now is the JOB + the wiring, so the Score phase only has to fill
 * in arithmetic — nothing has to be back-migrated. This is exactly the "job exists + wiring,
 * scoring math can be minimal" the LEDGER phase calls for.
 */
@Component
public class PredictionScoringJob {

    private static final Logger log = LoggerFactory.getLogger(PredictionScoringJob.class);
    private static final int PAGE = 500;

    private final PredictionLedgerRepository ledger;
    private final EventOutcomeRepository outcomes;
    private final PredictionLedgerService service;
    private final Clock clock;

    public PredictionScoringJob(PredictionLedgerRepository ledger, EventOutcomeRepository outcomes,
                                PredictionLedgerService service, Clock clock) {
        this.ledger = ledger;
        this.outcomes = outcomes;
        this.service = service;
        this.clock = clock;
    }

    /** 05:00 UTC on the 1st of each month. */
    @Scheduled(cron = "0 0 5 1 * *")
    @SchedulerLock(name = "predictor_scoring", lockAtMostFor = "PT1H", lockAtLeastFor = "PT10S")
    public void run() {
        Instant now = clock.instant();
        List<PredictionLedger> unjoined = ledger.findByOutcomeJoinedAtIsNull(PageRequest.of(0, PAGE));
        int joined = 0;
        for (PredictionLedger row : unjoined) {
            EventOutcome o = outcomes.findById(row.getEventId()).orElse(null);
            if (o == null || o.getFinalizedAt() == null) continue; // outcome not finalized yet
            // Minimal join now; brier/ape are computed in the Score phase (left null on purpose).
            service.joinOutcome(row.getId(), o.getSoldTotal(), o.getAttendance(), now, null, null);
            joined++;
        }
        if (joined > 0) {
            log.info("PredictionScoringJob: joined {} ledger render(s) to their outcome", joined);
        } else {
            log.debug("PredictionScoringJob: nothing to join");
        }
    }
}
