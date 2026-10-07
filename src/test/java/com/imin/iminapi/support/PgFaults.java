package com.imin.iminapi.support;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * A real write failure inside the code's own transaction: Postgres rejects INSERT/UPDATE of one row until closed.
 * Scoped to one row so other tests are untouched; always open it in try-with-resources.
 */
public final class PgFaults {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_]+");

    /** {@link AutoCloseable} without a checked exception, so try-with-resources needs no {@code throws}. */
    public interface Fault extends AutoCloseable {
        @Override
        void close();
    }

    private PgFaults() {}

    public static Fault failWrites(JdbcTemplate jdbc, String table, String column, UUID value) {
        requireIdentifier(table);
        requireIdentifier(column);
        if (value == null) throw new IllegalArgumentException("value is required");
        String name = "imin_test_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'injected test fault'; END $$");
        try {
            jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT OR UPDATE ON " + table
                    + " FOR EACH ROW WHEN (NEW." + column + " = '" + value + "'::uuid) EXECUTE FUNCTION " + name + "()");
        } catch (RuntimeException e) {
            try {
                jdbc.execute("DROP FUNCTION IF EXISTS " + name + "()");
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return () -> drop(jdbc, table, name);
    }

    /**
     * The UPDATE affects 0 rows for that id, as if a concurrent writer had changed it first; scoped to one row.
     * Other rows and INSERTs are untouched; always open it in try-with-resources.
     */
    public static Fault skipUpdates(JdbcTemplate jdbc, String table, String column, UUID value) {
        requireIdentifier(table);
        requireIdentifier(column);
        if (value == null) throw new IllegalArgumentException("value is required");
        String name = "imin_test_skip_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RETURN NULL; END $$");
        try {
            jdbc.execute("CREATE TRIGGER " + name + " BEFORE UPDATE ON " + table
                    + " FOR EACH ROW WHEN (NEW." + column + " = '" + value + "'::uuid) EXECUTE FUNCTION " + name + "()");
        } catch (RuntimeException e) {
            try {
                jdbc.execute("DROP FUNCTION IF EXISTS " + name + "()");
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return () -> drop(jdbc, table, name);
    }

    /**
     * Holds the row's writer inside its own transaction until released, as if it were slow; scoped to one row.
     * Join every writer thread before {@code close()}; always open it in try-with-resources.
     */
    public static Pause pauseWrites(DataSource ds, String table, String column, UUID value) {
        requireIdentifier(table);
        requireIdentifier(column);
        if (value == null) throw new IllegalArgumentException("value is required");
        long key = ThreadLocalRandom.current().nextLong(1, 1L << 31);
        String name = "imin_test_pause_" + UUID.randomUUID().toString().replace("-", "");
        Connection holder;
        try {
            holder = ds.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        Pause pause = new Pause(ds, holder, key, table, name);
        try {
            holder.setAutoCommit(true);
            try (Statement st = holder.createStatement()) {
                st.execute("SELECT pg_advisory_lock(" + key + ")");
                st.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS "
                        + "$$ BEGIN PERFORM pg_advisory_xact_lock_shared(" + key + "); RETURN NEW; END $$");
                st.execute("CREATE TRIGGER " + name + " BEFORE INSERT OR UPDATE ON " + table
                        + " FOR EACH ROW WHEN (NEW." + column + " = '" + value + "'::uuid) EXECUTE FUNCTION "
                        + name + "()");
            }
            return pause;
        } catch (SQLException | RuntimeException e) {
            RuntimeException failure = e instanceof RuntimeException r ? r : new IllegalStateException(e);
            try {
                pause.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * A held pause: the paused writer waits on an advisory lock that {@code holder} owns. Only the test thread
     * touches {@code holder}; {@link #awaitBlocked} polls on a connection of its own.
     */
    public static final class Pause implements AutoCloseable {
        private static final int DROP_LOCK_TIMEOUT_SECONDS = 10;
        private final DataSource ds;
        private final Connection holder;
        private final long key;
        private final String table;
        private final String name;
        private boolean held = true;
        private boolean closed;

        private Pause(DataSource ds, Connection holder, long key, String table, String name) {
            this.ds = ds;
            this.holder = holder;
            this.key = key;
            this.table = table;
            this.name = name;
        }

        /** Waits until a writer is blocked on this pause, else fails the test. */
        public void awaitBlocked(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_locks WHERE "
                         + "locktype = 'advisory' AND classid = 0 AND objid = ? AND objsubid = 1 AND NOT granted")) {
                ps.setLong(1, key);
                while (System.nanoTime() < deadline) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getLong(1) > 0) return;
                    }
                    Thread.sleep(10);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for a paused writer", e);
            }
            throw new AssertionError("no writer blocked on " + table + " within " + timeout);
        }

        /** Lets the paused writer continue. */
        public synchronized void release() {
            if (!held) return;
            try (Statement st = holder.createStatement()) {
                st.execute("SELECT pg_advisory_unlock(" + key + ")");
                held = false;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Releases, drops the trigger (bounded wait, so an unjoined writer fails fast), and returns the connection. */
        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            List<RuntimeException> failures = new ArrayList<>();
            List<Runnable> steps = List.of(this::release, this::dropTrigger,
                    // The pool keeps session state: never hand back a connection that still holds the key.
                    () -> exec("SELECT pg_advisory_unlock_all()"),
                    () -> {
                        try {
                            holder.close();
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            for (Runnable step : steps) {
                try {
                    step.run();
                } catch (RuntimeException e) {
                    failures.add(e);
                }
            }
            if (failures.isEmpty()) return;
            RuntimeException first = failures.get(0);
            failures.subList(1, failures.size()).forEach(first::addSuppressed);
            throw first;
        }

        private void dropTrigger() {
            RuntimeException failure = null;
            try {
                holder.setAutoCommit(false);
                try (Statement st = holder.createStatement()) {
                    st.execute("SET LOCAL lock_timeout = '" + DROP_LOCK_TIMEOUT_SECONDS + "s'");
                    st.execute("DROP TRIGGER IF EXISTS " + name + " ON " + table);
                    st.execute("DROP FUNCTION IF EXISTS " + name + "()");
                }
                holder.commit();
            } catch (SQLException e) {
                failure = new IllegalStateException(e);
                try {
                    holder.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
            }
            try {
                holder.setAutoCommit(true);
            } catch (SQLException e) {
                if (failure == null) failure = new IllegalStateException(e);
                else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        }

        private void exec(String sql) {
            try (Statement st = holder.createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static void drop(JdbcTemplate jdbc, String table, String name) {
        List<RuntimeException> failures = new ArrayList<>();
        for (String sql : List.of("DROP TRIGGER IF EXISTS " + name + " ON " + table,
                "DROP FUNCTION IF EXISTS " + name + "()")) {
            try {
                jdbc.execute(sql);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        if (failures.isEmpty()) return;
        RuntimeException first = failures.get(0);
        failures.subList(1, failures.size()).forEach(first::addSuppressed);
        throw first;
    }

    private static void requireIdentifier(String s) {
        if (s == null || !IDENTIFIER.matcher(s).matches()) {
            throw new IllegalArgumentException("not a plain identifier: " + s);
        }
    }
}
