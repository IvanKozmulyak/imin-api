package com.imin.iminapi.support;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One database per migration test on the JVM's single Postgres container; no Spring.
 * Each database is a copy of a template Flyway migrated to the requested version once per JVM, so its
 * {@code flyway_schema_history} ends at that version and a later {@code migrate()} applies only what follows.
 * A database holds one autocommit connection, reused by every statement, so a test pays one login, not one per query.
 */
public final class SharedPostgres {

    private static final Pattern PREFIX = Pattern.compile("[a-z0-9_]+");
    private static final Pattern DB_NAME = Pattern.compile("/([a-z0-9_]+)(\\?|$)");
    private static final AtomicInteger DATABASES = new AtomicInteger();
    /** Migrated templates by version; nobody connects to one after it is built, so CREATE ... TEMPLATE can copy it. */
    private static final TreeMap<MigrationVersion, String> TEMPLATES = new TreeMap<>();

    private SharedPostgres() {}

    /** A new database whose schema is exactly what Flyway leaves at {@code target} ("latest" or a version). */
    public static synchronized DataSource migratedDatabase(String prefix, String target) {
        return create(prefix, template(target));
    }

    /** Builds templates in ascending order, so each one continues from the previous instead of from empty. */
    public static synchronized void buildTemplates(String... targets) {
        for (String target : targets) template(target);
    }

    /** Drops a database this class made, ending any session still open on it. */
    public static void drop(DataSource ds) {
        ((SingleConnectionDataSource) ds).destroy();
        admin().execute("drop database if exists " + name(ds) + " with (force)");
    }

    private static DataSource create(String prefix, String from) {
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("database prefix must match [a-z0-9_]+: " + prefix);
        }
        String name = prefix + "_" + DATABASES.incrementAndGet();
        admin().execute("create database " + name + " template " + from);
        return dataSource(name);
    }

    // Built from the nearest lower template, so a chain of targets runs every migration once per JVM.
    private static String template(String target) {
        MigrationVersion version = "latest".equals(target) ? MigrationVersion.LATEST : MigrationVersion.fromVersion(target);
        String existing = TEMPLATES.get(version);
        if (existing != null) return existing;
        String name = "tmpl_" + target.replaceAll("[^a-z0-9]", "_");
        Map.Entry<MigrationVersion, String> base = TEMPLATES.lowerEntry(version);
        admin().execute("drop database if exists " + name + " with (force)");
        admin().execute("create database " + name + (base == null ? "" : " template " + base.getValue()));
        Flyway.configure().dataSource(new DriverManagerDataSource(url(name), user(), password()))
                .locations("classpath:db/migration").target(target).load().migrate();
        TEMPLATES.put(version, name);
        return name;
    }

    private static String name(DataSource ds) {
        Matcher m = DB_NAME.matcher(((SingleConnectionDataSource) ds).getUrl());
        if (!m.find()) throw new IllegalArgumentException("not a SharedPostgres database: " + ds);
        return m.group(1);
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(pg().getJdbcUrl(), user(), password()));
    }

    private static SingleConnectionDataSource dataSource(String name) {
        SingleConnectionDataSource ds = new SingleConnectionDataSource(url(name), user(), password(), true);
        ds.setAutoCommit(true);
        return ds;
    }

    private static String url(String name) {
        return pg().getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    private static PostgreSQLContainer pg() { return IntegrationTestConfig.Postgres.CONTAINER; }
    private static String user() { return pg().getUsername(); }
    private static String password() { return pg().getPassword(); }
}
