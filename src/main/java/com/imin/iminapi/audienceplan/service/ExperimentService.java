package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Splits one plan segment's members into a holdout and timing arms, and stores who went where. The split is a
 * Fisher-Yates shuffle of the id-sorted members with a stored seed, so it can be replayed.
 */
@Service
public class ExperimentService {

    private static final SecureRandom SEEDS = new SecureRandom();
    private static final int BATCH = 1000;
    private static final String INSERT_ASSIGNMENT =
            "INSERT INTO audience_assignments (experiment_id, membership_id, arm, assigned_at) VALUES (?, ?, ?, ?)";

    private final AudienceExperimentRepository experiments;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @PersistenceContext
    private EntityManager em;

    public ExperimentService(AudienceExperimentRepository experiments, JdbcTemplate jdbc, Clock clock) {
        this.experiments = experiments;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** {@code arms} keeps the requested arm order; every member is in exactly one list. */
    public record Split(List<UUID> holdout, Map<String, List<UUID>> arms) {}

    /** One stored experiment per non-empty holdout and per arm, keyed by arm. */
    public record Recorded(AudienceExperiment holdout, Map<String, AudienceExperiment> arms) {}

    public long newSeed() {
        return SEEDS.nextLong();
    }

    /**
     * Holdout is {@code floor(n × pct / 100)} only when {@code n ≥ holdoutMin}; the rest is split evenly in arm order,
     * the first arms taking one extra member each when it does not divide.
     */
    public static Split split(List<UUID> members, long seed, int holdoutPct, int holdoutMin, List<String> arms) {
        if (arms == null || arms.isEmpty()) throw new IllegalArgumentException("at least one arm is required");
        List<UUID> order = new ArrayList<>(members);
        Collections.sort(order);
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = order.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            UUID tmp = order.get(i);
            order.set(i, order.get(j));
            order.set(j, tmp);
        }
        int n = order.size();
        int holdout = n >= holdoutMin ? n * holdoutPct / 100 : 0;
        int rest = n - holdout;
        int base = rest / arms.size();
        int extra = rest % arms.size();

        Map<String, List<UUID>> out = new LinkedHashMap<>();
        int from = holdout;
        for (int k = 0; k < arms.size(); k++) {
            int size = base + (k < extra ? 1 : 0);
            out.put(arms.get(k), List.copyOf(order.subList(from, from + size)));
            from += size;
        }
        return new Split(List.copyOf(order.subList(0, holdout)), Collections.unmodifiableMap(out));
    }

    /** Writes the experiments and every assignment; runs inside the caller's transaction, before any campaign exists. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Recorded record(UUID orgId, UUID eventId, UUID planId, UUID planSegmentId, long seed, int holdoutPct,
                           Split split) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AudienceExperiment holdout = split.holdout().isEmpty() ? null
                : write(orgId, eventId, planId, planSegmentId, AudienceExperiment.ARM_HOLDOUT, seed, holdoutPct,
                        split.holdout(), now);
        Map<String, AudienceExperiment> arms = new LinkedHashMap<>();
        split.arms().forEach((arm, ids) -> arms.put(arm, write(orgId, eventId, planId, planSegmentId, arm, seed,
                holdoutPct, ids, now)));
        return new Recorded(holdout, Collections.unmodifiableMap(arms));
    }

    private AudienceExperiment write(UUID orgId, UUID eventId, UUID planId, UUID planSegmentId, String arm, long seed,
                                     int holdoutPct, List<UUID> members, Instant now) {
        AudienceExperiment e = new AudienceExperiment();
        e.setOrgId(orgId);
        e.setEventId(eventId);
        e.setPlanId(planId);
        e.setPlanSegmentId(planSegmentId);
        e.setArm(arm);
        e.setMembers(members.size());
        e.setSeed(seed);
        e.setHoldoutPct(holdoutPct);
        AudienceExperiment saved = experiments.save(e);
        // The experiment row must exist before the JDBC batch references it; one batched INSERT per 1,000 members.
        em.flush();
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        jdbc.batchUpdate(INSERT_ASSIGNMENT, members, BATCH, (ps, id) -> {
            ps.setObject(1, saved.getId());
            ps.setObject(2, id);
            ps.setString(3, arm);
            ps.setObject(4, at);
        });
        return saved;
    }
}
