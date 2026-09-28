package com.imin.iminapi.audienceplan.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/**
 * Deletes superseded audience plans older than {@link #KEEP}. Per event it only takes plans older than every plan an
 * experiment points to, oldest first, so what goes is always the head of a supersede chain: a kept plan never points
 * at a deleted one (superseded_by is ON DELETE SET NULL, which would make it current again).
 */
@Component
public class PlanPruner {

    static final Duration KEEP = Duration.ofDays(30);
    static final int BATCH = 500;
    static final int MAX_BATCHES = 20;

    // ORDER BY created_at, id keeps each event's deleted rows a prefix of its chain even when a batch stops midway.
    static final String PRUNE = """
            DELETE FROM audience_plans WHERE id IN (
              SELECT p.id FROM audience_plans p
               WHERE p.superseded_by IS NOT NULL
                 AND p.created_at < ?
                 AND NOT EXISTS (
                   SELECT 1 FROM audience_experiments x JOIN audience_plans r ON r.id = x.plan_id
                    WHERE r.event_id = p.event_id AND r.created_at <= p.created_at)
                 AND NOT EXISTS (
                   SELECT 1 FROM audience_experiments x
                     JOIN audience_plan_segments s ON s.id = x.plan_segment_id
                     JOIN audience_plans r ON r.id = s.plan_id
                    WHERE r.event_id = p.event_id AND r.created_at <= p.created_at)
               ORDER BY p.created_at, p.id
               LIMIT ?)""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batch;
    private final int maxBatches;

    @Autowired
    public PlanPruner(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this(jdbc, tx, clock, BATCH, MAX_BATCHES);
    }

    PlanPruner(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, int batch, int maxBatches) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.batch = batch;
        this.maxBatches = maxBatches;
    }

    /** Deleted plan rows (their segments cascade); at most {@link #MAX_BATCHES} batches, each its own transaction. */
    public int prune() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(KEEP));
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            Integer deleted = tx.execute(status -> jdbc.update(PRUNE, cutoff, batch));
            int n = deleted == null ? 0 : deleted;
            total += n;
            if (n < batch) break;
        }
        return total;
    }
}
