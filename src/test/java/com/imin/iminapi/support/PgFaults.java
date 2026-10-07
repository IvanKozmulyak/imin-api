package com.imin.iminapi.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
