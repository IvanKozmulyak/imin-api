package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.engine.CalibrationSource;
import com.imin.iminapi.audienceplan.engine.ResponseModel;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.OutcomeStore;
import com.imin.iminapi.audienceplan.repository.OutcomeStore.Calibration;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stored outcomes as observations: {@code own} = the org's invitation arms per class × fit, {@code imin} = all orgs
 * minus own, only once {@link #MIN_OTHER_ORGS} other orgs contributed; holdouts are never invitations. Served from a snapshot because the candidate builder asks per person.
 */
@Service
public class CalibrationService implements CalibrationSource {

    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);

    /** ponytail: other replicas see a rebuild within this delay; a shared version table would make it immediate. */
    static final Duration MAX_AGE = Duration.ofMinutes(10);

    /** The imin band of a cell counts only when at least this many other orgs sent invitations in it. */
    static final int MIN_OTHER_ORGS = 3;

    private final OutcomeStore store;
    private final Clock clock;
    private volatile Snapshot snapshot;

    public CalibrationService(OutcomeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** {@code orgs} = per cell, the orgs whose invitation arms are in it. */
    record Snapshot(Map<String, Counts> own, Map<String, Counts> imin, Map<String, Set<UUID>> orgs, int version,
                    Instant loadedAt) {
        static Snapshot empty(Instant at) {
            return new Snapshot(Map.of(), Map.of(), Map.of(), 0, at);
        }
    }

    @Override
    public Observations observations(UUID orgId, String classKey, ResponseModel.Fit fit) {
        Snapshot s = current();
        String cell = cell(classKey, fit.name().toLowerCase(Locale.ROOT));
        Counts own = s.own().getOrDefault(orgId + "|" + cell, Counts.ZERO);
        Set<UUID> contributors = s.orgs().getOrDefault(cell, Set.of());
        int others = contributors.size() - (contributors.contains(orgId) ? 1 : 0);
        if (others < MIN_OTHER_ORGS) return new Observations(Counts.ZERO, own);
        Counts all = s.imin().getOrDefault(cell, Counts.ZERO);
        // Both maps come from one rebuild, so the clamps only guard a hand-edited table.
        int invited = Math.max(0, all.invited() - own.invited());
        int bought = Math.min(invited, Math.max(0, all.bought() - own.bought()));
        return new Observations(new Counts(invited, bought), own);
    }

    /** 0 while nothing is stored; otherwise a hash of every row, so plans recompute when calibration changes. */
    @Override
    public int version() {
        return current().version();
    }

    /** Recomputes every calibration row from the stored outcomes, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Calibration> rebuild() {
        List<Calibration> rows = new ArrayList<>();
        Map<String, int[]> imin = new LinkedHashMap<>();
        for (Calibration c : store.orgCalibrationFromOutcomes()) {
            if (c.n() <= 0) continue;
            rows.add(c);
            String key = c.classKey() + "|" + c.genreFit() + "|" + c.arm();
            int[] sum = imin.computeIfAbsent(key, k -> new int[3]);
            sum[0] += c.n();
            sum[1] += c.bought();
            sum[2] += c.events();
        }
        imin.forEach((key, sum) -> {
            String[] k = key.split("\\|", -1);
            rows.add(new Calibration(OutcomeStore.SCOPE_IMIN, OutcomeStore.IMIN_ORG, k[0], k[1], k[2], sum[0], sum[1],
                    sum[2]));
        });
        store.replaceCalibration(rows, clock.instant());
        return rows;
    }

    /** Drops the snapshot so the next read loads the stored rows. */
    public void invalidate() {
        snapshot = null;
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        Instant now = clock.instant();
        if (s != null && now.isBefore(s.loadedAt().plus(MAX_AGE)) && !now.isBefore(s.loadedAt())) return s;
        synchronized (this) {
            s = snapshot;
            if (s != null && now.isBefore(s.loadedAt().plus(MAX_AGE)) && !now.isBefore(s.loadedAt())) return s;
            snapshot = s = load(s, now);
            return s;
        }
    }

    /** A failed read keeps the previous snapshot (or none) rather than failing the plan. */
    private Snapshot load(Snapshot previous, Instant now) {
        try {
            return build(store.calibration(), now);
        } catch (RuntimeException e) {
            log.warn("CalibrationService: calibration read failed, keeping the previous snapshot: {} {}",
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
            return previous != null
                    ? new Snapshot(previous.own(), previous.imin(), previous.orgs(), previous.version(), now)
                    : Snapshot.empty(now);
        }
    }

    static Snapshot build(List<Calibration> rows, Instant now) {
        if (rows.isEmpty()) return Snapshot.empty(now);
        Map<String, Counts> own = new HashMap<>();
        Map<String, Counts> imin = new HashMap<>();
        Map<String, Set<UUID>> orgs = new HashMap<>();
        StringBuilder content = new StringBuilder("response-calibration/1");
        for (Calibration c : rows) {
            content.append('|').append(c.scope()).append(':').append(c.orgId()).append(':').append(c.classKey())
                    .append(':').append(c.genreFit()).append(':').append(c.arm()).append(':').append(c.n())
                    .append(':').append(c.bought());
            if (AudienceExperiment.ARM_HOLDOUT.equals(c.arm()) || c.bought() > c.n()) continue;
            Counts counts = new Counts(c.n(), c.bought());
            String cell = cell(c.classKey(), c.genreFit());
            if (OutcomeStore.SCOPE_IMIN.equals(c.scope())) imin.merge(cell, counts, Counts::plus);
            else {
                own.merge(c.orgId() + "|" + cell, counts, Counts::plus);
                if (c.n() > 0) orgs.computeIfAbsent(cell, k -> new HashSet<>()).add(c.orgId());
            }
        }
        Map<String, Set<UUID>> frozen = new HashMap<>();
        orgs.forEach((cell, ids) -> frozen.put(cell, Set.copyOf(ids)));
        return new Snapshot(Map.copyOf(own), Map.copyOf(imin), Map.copyOf(frozen), version(content.toString()), now);
    }

    private static String cell(String classKey, String genreFit) {
        return classKey + "|" + genreFit;
    }

    /** First four bytes of a SHA-256; never 0, which means "no calibration". */
    private static int version(String content) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            int v = ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
            return v == 0 ? 1 : v;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
