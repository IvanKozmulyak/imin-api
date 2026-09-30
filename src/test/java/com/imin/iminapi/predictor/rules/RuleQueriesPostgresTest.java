package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The rule engine's event queries on real Postgres 17, where a bad bind type fails and H2 does not. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestRateLimitConfig.class)
class RuleQueriesPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.docker.compose.enabled", () -> "false");
    }

    private static final Instant NIGHT = Instant.parse("2026-11-14T22:00:00Z");

    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private UUID orgId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        wipe();
        Organization o = new Organization();
        o.setName("Org");
        o.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("h@test.example");
        o.setCountry("FR");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("o-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        userId = users.save(u).getId();
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private Event ev(String city, EventStatus status) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(userId);
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVenueCity(city);
        e.setGenre("Techno");
        e.setStartsAt(NIGHT);
        e.setStatus(status);
        if (status != EventStatus.DRAFT) e.setPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));
        return events.save(e);
    }

    @Test
    void cityAndOrgQueriesRunOnPostgres() {
        Event live = ev("Paris", EventStatus.LIVE);
        Event draft = ev("Paris", EventStatus.DRAFT);
        ev("Paris", EventStatus.CANCELLED);
        ev("Lyon", EventStatus.LIVE);
        Event hidden = ev("Paris", EventStatus.LIVE);
        hidden.setVisibility(EventVisibility.PRIVATE);
        events.save(hidden);
        Instant from = NIGHT.minusSeconds(3600);
        Instant to = NIGHT.plusSeconds(3600);

        assertThat(events.findCityEventsBetween("paris", from, to)).extracting(Event::getId).containsExactly(live.getId());
        assertThat(events.findOrgEventsInCityBetween(orgId, "paris", from, to)).extracting(Event::getId)
                .containsExactlyInAnyOrder(live.getId(), draft.getId(), hidden.getId());
        assertThat(events.existsPublishedInCitySince("paris", from)).isTrue();
        assertThat(events.existsPublishedInCitySince("marseille", from)).isFalse();
        assertThat(events.existsPublishedInCitySince("paris", to)).isFalse();
    }

    @Test
    void existsPublishedIgnoresCancelledAndPrivate() {
        ev("Paris", EventStatus.CANCELLED);
        Event hidden = ev("Paris", EventStatus.LIVE);
        hidden.setVisibility(EventVisibility.PRIVATE);
        events.save(hidden);
        Instant since = NIGHT.minusSeconds(3600);

        assertThat(events.existsPublishedInCitySince("paris", since)).isFalse();
        ev("Paris", EventStatus.PAST);
        assertThat(events.existsPublishedInCitySince("paris", since)).isTrue();
    }
}
