package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.data.domain.PageRequest;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The tier Stripe-id write on Postgres 17: a mixed-case stored currency and a moved price. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class TicketTierStripeIdsPostgresTest {

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

    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID orgId;
    private UUID userId;
    private UUID eventId;
    private UUID tierId;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("Tier ids");
        o.setSlug("tip-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("tip@example.test");
        o.setCountry("DE");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("tip-" + UUID.randomUUID() + "@example.test");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        userId = users.save(u).getId();

        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(userId);
        e.setName("Night");
        e.setSlug("tip-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCurrency("EUR");
        eventId = events.save(e).getId();
        jdbc.update("UPDATE events SET currency = 'EUR' WHERE id = ?", eventId);

        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(1500);
        t.setQuantity(100);
        tierId = tiers.save(t).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ticket_tiers WHERE event_id IN (SELECT id FROM events WHERE org_id = ?)", orgId);
        jdbc.update("DELETE FROM events WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM users WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    void landsWhenPriceMatches_andUpperCaseStoredCurrencyMatchesLowerCaseSent() {
        int rows = tiers.updateStripeIdsIfPriceUnchanged(tierId, "prod_pg", "price_pg", 1500, "eur");

        assertThat(rows).isEqualTo(1);
        assertThat(stored()).containsEntry("stripe_product_id", "prod_pg")
                .containsEntry("stripe_price_id", "price_pg");
    }

    @Test
    void noRowWhenPriceMoved() {
        jdbc.update("UPDATE ticket_tiers SET price_minor = 2000 WHERE id = ?", tierId);

        int rows = tiers.updateStripeIdsIfPriceUnchanged(tierId, "prod_old", "price_old", 1500, "eur");

        assertThat(rows).isZero();
        assertThat(stored()).containsEntry("stripe_product_id", null)
                .containsEntry("stripe_price_id", null);
    }

    @Test
    void sweepCandidatesAndClaim_onPostgres() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Timestamp settled = Timestamp.from(now.minus(10, ChronoUnit.MINUTES));
        jdbc.update("UPDATE events SET updated_at = ? WHERE id = ?", settled, eventId);
        Event past = new Event();
        past.setOrgId(orgId);
        past.setCreatedBy(userId);
        past.setName("Over");
        past.setSlug("tip-" + UUID.randomUUID().toString().substring(0, 8));
        past.setVisibility(EventVisibility.PUBLIC);
        past.setStatus(EventStatus.PAST);
        past.setCurrency("EUR");
        UUID pastId = events.save(past).getId();
        jdbc.update("UPDATE events SET updated_at = ? WHERE id = ?", settled, pastId);
        TicketTier t = new TicketTier();
        t.setEventId(pastId);
        t.setName("GA");
        t.setPriceMinor(1500);
        t.setQuantity(100);
        tiers.save(t);

        List<Object[]> due = tiers.findStripeSyncSweepCandidates(List.of(EventStatus.DRAFT, EventStatus.LIVE),
                now.minus(2, ChronoUnit.MINUTES), now, PageRequest.of(0, 25));

        assertThat(due).extracting(r -> (UUID) r[0]).containsExactly(tierId);
        assertThat(((Number) due.get(0)[1]).intValue()).isZero();
        Instant next = now.plus(5, ChronoUnit.MINUTES);
        assertThat(tiers.claimStripeSyncSweep(tierId, 0, next, now)).isEqualTo(1);
        assertThat(tiers.claimStripeSyncSweep(tierId, 0, next, now)).isZero();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT stripe_sync_attempts, stripe_sync_next_at FROM ticket_tiers WHERE id = ?", tierId);
        assertThat(((Number) row.get("stripe_sync_attempts")).intValue()).isEqualTo(1);
        assertThat(((Timestamp) row.get("stripe_sync_next_at")).toInstant()).isEqualTo(next);
    }

    private Map<String, Object> stored() {
        return jdbc.queryForMap("SELECT stripe_product_id, stripe_price_id FROM ticket_tiers WHERE id = ?", tierId);
    }
}
