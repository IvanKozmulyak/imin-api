package com.imin.iminapi.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

/** V162 on Postgres 17, one database per test inside one container. */
@Testcontainers(disabledWithoutDocker = true)
class DateCheckMigrationPostgresTest extends DateCheckMigrationScenarios {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    private static int databases;

    @Override
    DataSource freshDatabase() {
        String name = "v162_" + (++databases);
        new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()))
                .execute("create database " + name);
        String url = PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword());
    }
}
