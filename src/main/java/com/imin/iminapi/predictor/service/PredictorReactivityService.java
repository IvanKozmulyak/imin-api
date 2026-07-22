package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.ReforecastTrigger;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Score/re-forecast reactivity to organizer actions (task scope B). Listens AFTER_COMMIT to
 * {@link PredictorReactivityEvents} from the event mutation path and, off-thread, keeps the
 * predictor in step with what the organizer just did:
 *
 * <ul>
 *   <li><b>Draft edited</b> → re-score the pre-publish estimate, but ONLY if the event already has
 *       a prediction (never auto-score an event the organizer has not scored). Input-hash
 *       short-circuit skips no-op LLM calls.</li>
 *   <li><b>Live edited</b> → re-forecast recompute (trigger {@code EDIT}).</li>
 *   <li><b>Published</b> → a baseline re-forecast the moment a previously-scored draft goes live.</li>
 * </ul>
 *
 * <p>System-initiated runs BYPASS the per-organizer LLM quota (they are not organizer requests)
 * but are bounded two ways: a leading-edge debounce (~{@code action-debounce-seconds}, default
 * 120s) collapses autosave bursts into one recompute per burst, and a per-event daily cap
 * ({@code system-rescores-per-event-per-day}, default 10) stops a pathological edit loop from
 * burning budget — a WARN is logged when it trips. Both counters are in-memory (single-instance
 * deploy); the ledger, not this map, is the source of truth.
 */
@Service
public class PredictorReactivityService {

    private static final Logger log = LoggerFactory.getLogger(PredictorReactivityService.class);

    private final EventRepository events;
    private final PredictionScoringPipeline pipeline;
    private final PredictionLedgerRepository ledgerRepo;
    private final ReforecastService reforecast;
    private final PredictorProperties props;
    private final Clock clock;

    private final Map<UUID, Instant> lastAction = new ConcurrentHashMap<>();
    private final Map<UUID, DayCount> systemCounts = new ConcurrentHashMap<>();

    public PredictorReactivityService(EventRepository events, PredictionScoringPipeline pipeline,
                                      PredictionLedgerRepository ledgerRepo, ReforecastService reforecast,
                                      PredictorProperties props, Clock clock) {
        this.events = events;
        this.pipeline = pipeline;
        this.ledgerRepo = ledgerRepo;
        this.reforecast = reforecast;
        this.props = props;
        this.clock = clock;
    }

    private record DayCount(LocalDate day, int count) {}

    // ---- listeners -------------------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onEventMutated(PredictorReactivityEvents.EventMutated ev) {
        try {
            Event e = events.findActive(ev.eventId()).orElse(null);
            if (e == null) return;
            if (e.getStatus() == EventStatus.DRAFT) {
                draftRescore(e);
            } else if (e.getStatus() == EventStatus.LIVE) {
                liveReforecast(e.getId());
            }
        } catch (Exception ex) {
            log.warn("[reactivity] onEventMutated failed for {}: {}", ev.eventId(), ex.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onEventPublished(PredictorReactivityEvents.EventPublished ev) {
        try {
            if (!hasPrediction(ev.eventId())) return; // baseline only for previously-scored drafts
            if (!underDailyCap(ev.eventId())) {
                warnCapped(ev.eventId());
                return;
            }
            reforecast.recompute(ev.eventId(), ReforecastTrigger.PUBLISH); // immediate baseline (no debounce)
        } catch (Exception ex) {
            log.warn("[reactivity] onEventPublished failed for {}: {}", ev.eventId(), ex.getMessage());
        }
    }

    // ---- actions ---------------------------------------------------------------

    private void draftRescore(Event e) {
        if (!hasPrediction(e.getId())) return;                 // never auto-score an unscored event
        if (!acceptWithinDebounce(e.getId())) return;          // coalesce autosave bursts
        PredictionInputSnapshot snap = pipeline.snapshot(e);
        String hash = snap.sha256();
        if (hash.equals(latestPrePublishHash(e.getId()))) return; // no material change → no LLM call
        if (!underDailyCap(e.getId())) { warnCapped(e.getId()); return; }
        pipeline.score(e, snap); // system-initiated: bypasses the per-organizer daily quota
    }

    private void liveReforecast(UUID eventId) {
        if (!acceptWithinDebounce(eventId)) return;
        if (!underDailyCap(eventId)) { warnCapped(eventId); return; }
        reforecast.recompute(eventId, ReforecastTrigger.EDIT);
    }

    // ---- debounce + daily cap --------------------------------------------------

    private boolean acceptWithinDebounce(UUID eventId) {
        Instant now = clock.instant();
        Instant last = lastAction.get(eventId);
        if (last != null && Duration.between(last, now).getSeconds() < props.getActionDebounceSeconds()) {
            return false;
        }
        lastAction.put(eventId, now);
        return true;
    }

    private boolean underDailyCap(UUID eventId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        DayCount dc = systemCounts.get(eventId);
        int used = (dc != null && dc.day().equals(today)) ? dc.count() : 0;
        if (used >= props.getSystemRescoresPerEventPerDay()) return false;
        systemCounts.put(eventId, new DayCount(today, used + 1));
        return true;
    }

    private void warnCapped(UUID eventId) {
        log.warn("[reactivity] event {} hit the daily system-rescore cap ({}) — skipping",
                eventId, props.getSystemRescoresPerEventPerDay());
    }

    // ---- ledger reads ----------------------------------------------------------

    private boolean hasPrediction(UUID eventId) {
        for (PredictionLedger r : ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId)) {
            if (r.getSurface() == PredictionSurface.PRE_PUBLISH) return true;
        }
        return false;
    }

    private String latestPrePublishHash(UUID eventId) {
        for (PredictionLedger r : ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId)) {
            if (r.getSurface() == PredictionSurface.PRE_PUBLISH) return r.getInputSnapshotHash();
        }
        return null;
    }
}
