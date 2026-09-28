package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.dto.AudiencePlanOutcomeResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePlanOutcomeResponse.Arm;
import com.imin.iminapi.audienceplan.dto.AudiencePlanOutcomeResponse.Range;
import com.imin.iminapi.audienceplan.dto.AudiencePlanOutcomeResponse.Segment;
import com.imin.iminapi.audienceplan.engine.OutcomeMath;
import com.imin.iminapi.audienceplan.engine.OutcomeMath.LiftStatus;
import com.imin.iminapi.audienceplan.engine.OutcomeMath.Phase;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.OutcomeStore;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.Experiment;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.StoredArm;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.StoredEvent;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.Tally;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads an event's outcome: computed on request while the doors are open, the stored collection afterwards. */
@Service
public class OutcomeService {

    private final AudiencePlanAccess access;
    private final EventRepository events;
    private final OutcomeStore store;
    private final OutcomeCollector collector;
    private final AudiencePlanLogic logic;
    private final Clock clock;

    public OutcomeService(AudiencePlanAccess access, EventRepository events, OutcomeStore store,
                          OutcomeCollector collector, AudiencePlanLogic logic, Clock clock) {
        this.access = access;
        this.events = events;
        this.store = store;
        this.collector = collector;
        this.logic = logic;
        this.clock = clock;
    }

    /** One arm as the response builds it, whichever phase it came from. */
    private record Row(UUID experimentId, UUID planSegmentId, String classKey, String genreFit, Range plannedRate,
                       String plannedConfidence, String arm, Tally tally) {}

    @Transactional(readOnly = true)
    public AudiencePlanOutcomeResponse outcome(UUID orgId, UUID eventId) {
        access.requireEnabled(orgId);
        Event event = events.findActive(eventId).filter(e -> orgId.equals(e.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("Audience plan"));
        Instant now = clock.instant();
        Instant doorClose = OutcomeMath.doorClose(event.getStartsAt(), event.getEndsAt());
        List<Experiment> experiments = store.experiments(orgId, eventId);
        boolean invited = !experiments.isEmpty();

        if (doorClose == null || now.isBefore(doorClose)) {
            OutcomeCollector.Computed c = collector.compute(orgId, eventId, now, now);
            List<Row> rows = new ArrayList<>();
            for (Experiment x : c.experiments()) rows.add(row(x, c.tallies().getOrDefault(x.id(), Tally.ZERO)));
            List<UUID> campaigns = experiments.stream().map(Experiment::campaignId).filter(Objects::nonNull).toList();
            Instant nextWave = store.nextScheduled(orgId, campaigns, now).orElse(null);
            return response(eventId, Phase.LIVE.key(), invited, doorClose, null, c.newGuests(), nextWave, rows);
        }

        Optional<StoredEvent> stored = store.storedEvent(orgId, eventId);
        if (stored.isEmpty()) {
            return response(eventId, Phase.PENDING.key(), invited, doorClose, null, null, null, List.of());
        }
        // Stored rows cascade with their experiment, so walking the experiments keeps their order and loses none.
        Map<UUID, StoredArm> byExperiment = new HashMap<>();
        for (StoredArm a : store.storedArms(orgId, eventId)) byExperiment.put(a.experimentId(), a);
        List<Row> rows = new ArrayList<>();
        for (Experiment x : experiments) {
            StoredArm a = byExperiment.get(x.id());
            if (a == null) continue;
            rows.add(new Row(a.experimentId(), a.planSegmentId(), a.classKey(), a.genreFit(), planned(x), x.confidence(),
                    a.arm(), a.tally()));
        }
        StoredEvent s = stored.get();
        return response(eventId, s.phase(), invited, doorClose, s.computedAt(), s.newGuests(), null, rows);
    }

    private AudiencePlanOutcomeResponse response(UUID eventId, String phase, boolean invited, Instant doorClose,
                                                 Instant computedAt, Integer newGuests, Instant nextWave,
                                                 List<Row> rows) {
        int minimum = logic.logic().experiments().holdoutMinMailable();
        // One segment per plan segment, in the order its first arm appears; an arm without one stands alone.
        Map<Object, List<Row>> bySegment = new LinkedHashMap<>();
        for (Row r : rows) {
            Object key = r.planSegmentId() != null ? r.planSegmentId() : r.experimentId();
            bySegment.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<Segment> segments = new ArrayList<>();
        for (List<Row> group : bySegment.values()) {
            Row holdout = group.stream().filter(r -> AudienceExperiment.ARM_HOLDOUT.equals(r.arm())).findFirst()
                    .orElse(null);
            List<Arm> arms = new ArrayList<>();
            if (holdout != null) arms.add(arm(holdout, null, minimum));
            for (Row r : group) if (r != holdout) arms.add(arm(r, holdout, minimum));
            Row first = group.get(0);
            segments.add(new Segment(first.planSegmentId(), first.classKey(), first.genreFit(), first.plannedRate(),
                    first.plannedConfidence(), List.copyOf(arms)));
        }
        return new AudiencePlanOutcomeResponse(eventId, phase, invited, doorClose, computedAt, OutcomeMath.INTERVAL_LEVEL,
                minimum, newGuests, nextWave, List.copyOf(segments));
    }

    private static Arm arm(Row r, Row holdout, int minimum) {
        Tally t = r.tally();
        boolean isHoldout = holdout == null && AudienceExperiment.ARM_HOLDOUT.equals(r.arm());
        LiftStatus status = OutcomeMath.liftStatus(isHoldout,
                holdout == null ? null : holdout.tally().members(), t.members(), minimum);
        Range lift = status == LiftStatus.OK
                ? range(OutcomeMath.lift(t.bought(), t.members(), holdout.tally().bought(), holdout.tally().members()))
                : null;
        return new Arm(r.arm(), r.experimentId(), t.members(), t.sent(), t.bought(), t.tickets(), t.attended(),
                t.unsubscribed(), t.complained(), range(OutcomeMath.rate(t.bought(), t.members())), lift,
                status.key());
    }

    private static Row row(Experiment x, Tally t) {
        return new Row(x.id(), x.planSegmentId(), x.classKey(), x.genreFit(), planned(x), x.confidence(), x.arm(), t);
    }

    private static Range planned(Experiment x) {
        if (x.rateLow() == null || x.rateMid() == null || x.rateHigh() == null) return null;
        return new Range(x.rateLow(), x.rateMid(), x.rateHigh());
    }

    private static Range range(OutcomeMath.Range r) {
        return r == null ? null : new Range(r.low(), r.mid(), r.high());
    }
}
