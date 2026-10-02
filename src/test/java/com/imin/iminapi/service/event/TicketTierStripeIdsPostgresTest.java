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
        UUID userId = users.save(u).getId();

        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(userId);
        e.setName("Night");
        e.setSlug("tip-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCurrency("EUR");
        UUID eventId = events.save(e).getId();
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

    private Map<String, Object> stored() {
        return jdbc.queryForMap("SELECT stripe_product_id, stripe_price_id FROM ticket_tiers WHERE id = ?", tierId);
    }
}
