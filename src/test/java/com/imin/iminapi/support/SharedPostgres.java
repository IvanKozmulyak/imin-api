package com.imin.iminapi.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * One database per migration test on the JVM's single Postgres container; no Spring.
 * Flyway and JDBC run on it alone, so its {@code flyway_schema_history} starts empty.
 */
public final class SharedPostgres {

    private static final Pattern PREFIX = Pattern.compile("[a-z0-9_]+");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private SharedPostgres() {}

    public static DataSource freshDatabase(String prefix) {
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("database prefix must match [a-z0-9_]+: " + prefix);
        }
        PostgreSQLContainer pg = IntegrationTestConfig.Postgres.CONTAINER;
        String name = prefix + "_" + DATABASES.incrementAndGet();
        new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()))
                .execute("create database " + name);
        String url = pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword());
    }
}
