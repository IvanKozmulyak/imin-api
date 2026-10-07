package com.imin.iminapi.support;

import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.storage.MediaStorage;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;

/** Beans of the shared integration context. The Postgres container belongs to the JVM, not to a context. */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfig {

    /** Started on first use; no context owns it, so cache eviction never stops it. Ryuk removes it at exit. */
    static final class Postgres {
        static final PostgreSQLContainer CONTAINER = start();

        private static PostgreSQLContainer start() {
            PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17-alpine");
            pg.start();
            return pg;
        }
    }

    // Replaces the yaml's H2 datasource: Boot binds spring.datasource.* only when this bean is missing.
    @Bean
    JdbcConnectionDetails iminPostgresConnectionDetails() {
        PostgreSQLContainer pg = Postgres.CONTAINER;
        return new JdbcConnectionDetails() {
            @Override public String getUsername() { return pg.getUsername(); }
            @Override public String getPassword() { return pg.getPassword(); }
            @Override public String getJdbcUrl() { return pg.getJdbcUrl(); }
            @Override public String getDriverClassName() { return "org.postgresql.Driver"; }
        };
    }

    @Bean @Primary
    RecordingEmailService iminRecordingEmailService() {
        return new RecordingEmailService();
    }

    @Bean
    RecordingRateLimiter iminRecordingRateLimiter() {
        return new RecordingRateLimiter();
    }

    @Bean
    MediaStorage iminInMemoryMediaStorage() {
        return new PausableMediaStorage("https://test-media.invalid/");
    }

    @Bean @Primary
    MutableClock iminMutableClock() {
        return new MutableClock();
    }

    @Bean
    PropertyFlips iminPropertyFlips() {
        return new PropertyFlips();
    }

    @Bean
    IminFixtures iminFixtures(OrganizationRepository orgs, UserRepository users, EventRepository events,
                              TicketTierRepository tiers, OrderRepository orders, TicketRepository tickets,
                              Clock clock) {
        return new IminFixtures(orgs, users, events, tiers, orders, tickets, clock);
    }

    @Bean
    AuditRows iminAuditRows(AuditLogRepository auditLogs) {
        return new AuditRows(auditLogs);
    }
}
