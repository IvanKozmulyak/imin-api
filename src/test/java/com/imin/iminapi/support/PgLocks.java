package com.imin.iminapi.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.TimeUnit;

/** Asks Postgres whether a statement is queued on a lock, so a race test waits for the block instead of sleeping. */
public final class PgLocks {

    private static final long TIMEOUT_SECONDS = 30;

    private PgLocks() {}

    /** Returns once a statement matching {@code queryRegex} waits on a lock in this database; fails after 30 s. */
    public static void awaitLockWait(JdbcTemplate jdbc, String queryRegex, String description) {
        awaitLockWaits(jdbc, queryRegex, 1, description);
    }

    /** Returns once {@code waiters} statements matching {@code queryRegex} wait on a lock; fails after 30 s. */
    public static void awaitLockWaits(JdbcTemplate jdbc, String queryRegex, int waiters, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            // Each autocommit query reads a fresh pg_stat_activity snapshot.
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database()"
                    + " and wait_event_type = 'Lock' and query ~* ?", Integer.class, queryRegex);
            if (waiting != null && waiting >= waiters) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for: " + description + " (" + queryRegex + ")", e);
            }
        }
        throw new AssertionError(description + ": " + waiters + " statement(s) matching " + queryRegex
                + " did not wait on a lock within " + TIMEOUT_SECONDS + " s");
    }
}
