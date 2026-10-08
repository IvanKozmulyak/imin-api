package com.imin.iminapi.predictor.sources.prim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.predictor.model.TransitDisruption;
import com.imin.iminapi.predictor.model.TransitSyncState;
import com.imin.iminapi.predictor.repository.TransitDisruptionRepository;
import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Disruption;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Period;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Snapshot;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Status;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the PRIM snapshot and its poll state (V175). No ON CONFLICT, so H2 runs it too. */
@Component
public class PrimWriter {

    private static final String SOURCE = TransitSyncState.IDFM_PRIM;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TransitDisruptionRepository disruptions;
    private final TransitSyncStateRepository states;

    public PrimWriter(TransitDisruptionRepository disruptions, TransitSyncStateRepository states) {
        this.disruptions = disruptions;
        this.states = states;
    }

    /** Swaps every stored row of the source for the snapshot and stamps the state ok, in one transaction. */
    @Transactional
    public int replace(Snapshot snapshot, Instant syncedAt) {
        disruptions.deleteBySource(SOURCE);
        List<TransitDisruption> rows = new ArrayList<>();
        for (Disruption d : snapshot.disruptions()) {
            TransitDisruption row = new TransitDisruption();
            row.setSource(SOURCE);
            row.setDisruptionId(d.id());
            row.setCause(d.cause());
            row.setSeverity(d.severity());
            row.setKind(d.kind());
            row.setTitle(d.title());
            row.setLinesJson(json(d.lines().stream().map(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ref", l.ref());
                m.put("label", l.label());
                m.put("mode", l.mode());
                m.put("level", l.level());
                return m;
            }).toList()));
            row.setPeriodsJson(json(d.periods().stream()
                    .map(p -> Map.of("begin", p.begin().toString(), "end", p.end().toString())).toList()));
            row.setFirstBegin(d.periods().stream().map(Period::begin).min(Comparator.naturalOrder()).orElseThrow());
            row.setLastEnd(d.periods().stream().map(Period::end).max(Comparator.naturalOrder()).orElseThrow());
            row.setLastUpdate(d.lastUpdate());
            row.setSyncedAt(syncedAt);
            rows.add(row);
        }
        disruptions.saveAll(rows);
        TransitSyncState state = states.findById(SOURCE).orElseGet(this::fresh);
        state.setSyncedAt(syncedAt);
        state.setFeedUpdatedAt(snapshot.feedUpdatedAt());
        state.setDisruptionCount(rows.size());
        state.setLastStatus(Status.OK.wire());
        state.setLastAttemptAt(syncedAt);
        states.save(state);
        return rows.size();
    }

    /** A poll that did not succeed: only the attempt is recorded; stored rows and {@code synced_at} stay. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(Status status, Instant attemptedAt) {
        TransitSyncState state = states.findById(SOURCE).orElseGet(this::fresh);
        state.setLastStatus(status.wire());
        state.setLastAttemptAt(attemptedAt);
        states.save(state);
    }

    private TransitSyncState fresh() {
        TransitSyncState s = new TransitSyncState();
        s.setSource(SOURCE);
        return s;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
