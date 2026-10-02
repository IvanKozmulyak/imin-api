package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.CheckoutAttribution;
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
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unpublish racing reserve and free checkout on the same event, on Postgres 17 (READ COMMITTED). */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class UnpublishCheckoutRacePostgresTest {

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

    private static final UUID TIER_A = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID TIER_B = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final String SOLD_MESSAGE =
            "Cannot unpublish: tickets have been sold. Cancel the event and refund buyers first.";
    private static final String CHECKOUT_MESSAGE =
            "Cannot unpublish: a checkout is in progress. Try again once the checkout session expires.";

    @MockitoBean StripeProductService stripeProductService;

    @Autowired EventService eventService;
    @Autowired InventoryService inventoryService;
    @Autowired FreeCheckoutService freeCheckoutService;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private UUID orgId;
    private AuthPrincipal principal;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("Unpublish race");
        o.setSlug("upr-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("upr@example.test");
        o.setCountry("DE");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("upr-" + UUID.randomUUID() + "@example.test");
        u.setFirstName("Ada");
        u.setOrgId(orgId);
        u.setRole(UserRole.MEMBER);
        principal = new AuthPrincipal(users.save(u).getId(), orgId, UserRole.MEMBER, UUID.randomUUID());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(principal.userId());
        e.setName("Night");
        e.setSlug("upr-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setCurrency("EUR");
        e.setStartsAt(now.plus(10, ChronoUnit.DAYS));
        e.setEndsAt(now.plus(10, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS));
        e.setPublishedAt(now.minus(2, ChronoUnit.DAYS));
        eventId = events.save(e).getId();

        insertTier(TIER_A, 0);
        insertTier(TIER_B, 1);
    }

    @AfterEach
    void tearDown() {
        String tiersOfOrg = "SELECT t.id FROM ticket_tiers t JOIN events e ON e.id = t.event_id WHERE e.org_id = ?";
        String eventsOfOrg = "SELECT id FROM events WHERE org_id = ?";
        jdbc.update("DELETE FROM tickets WHERE event_id IN (" + eventsOfOrg + ")", orgId);
        jdbc.update("DELETE FROM orders WHERE event_id IN (" + eventsOfOrg + ")", orgId);
        jdbc.update("DELETE FROM ticket_reservations WHERE tier_id IN (" + tiersOfOrg + ")", orgId);
        jdbc.update("DELETE FROM ticket_tier_milestones WHERE tier_id IN (" + tiersOfOrg + ")", orgId);
        jdbc.update("DELETE FROM audit_logs WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM ticket_tiers WHERE event_id IN (" + eventsOfOrg + ")", orgId);
        jdbc.update("DELETE FROM events WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM users WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    @Timeout(60)
    void unpublishHoldsTiers_reserveWaitsThenIsRefused() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch unpublished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Event> cached = new AtomicReference<>();
        try {
            Future<?> organizer = pool.submit(() -> tx.executeWithoutResult(s -> {
                eventService.unpublish(principal, eventId);
                unpublished.countDown();
                await(release);
            }));
            assertThat(unpublished.await(15, TimeUnit.SECONDS)).isTrue();

            Future<UUID> buyer = pool.submit(() -> tx.execute(s -> {
                cached.set(events.findById(eventId).orElseThrow());
                return inventoryService.reserve(TIER_A, 2, Instant.now().plus(30, ChronoUnit.MINUTES), null);
            }));
            Thread.sleep(500);
            assertThat(buyer.isDone()).as("reserve waits for unpublish's tier lock").isFalse();

            release.countDown();
            organizer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> buyer.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(api.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    });
            // The buyer's own entity still says LIVE: the refusal came from the scalar status read.
            assertThat(cached.get().getStatus()).isEqualTo(EventStatus.LIVE);

            assertThat(eventStatus()).isEqualTo("DRAFT");
            assertThat(tierInt(TIER_A, "reserved")).isZero();
            assertThat(heldReservations(TIER_A)).isZero();
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    @Test
    @Timeout(60)
    void reserveHoldsSecondTier_unpublishWaitsThen409CheckoutInProgress() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> buyer = pool.submit(() -> tx.executeWithoutResult(s -> {
                inventoryService.reserve(TIER_B, 2, Instant.now().plus(30, ChronoUnit.MINUTES), null);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> organizer = pool.submit(() -> eventService.unpublish(principal, eventId));
            Thread.sleep(500);
            assertThat(organizer.isDone()).as("unpublish waits for the buyer's tier lock").isFalse();

            release.countDown();
            buyer.get(10, TimeUnit.SECONDS);
            assertConflict(organizer, CHECKOUT_MESSAGE);

            assertThat(eventStatus()).isEqualTo("LIVE");
            assertThat(tierInt(TIER_B, "reserved")).isEqualTo(2);
            assertThat(heldReservations(TIER_B)).isEqualTo(1);
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    @Test
    @Timeout(60)
    void freeOrderHoldsTier_unpublishWaitsThen409Sold() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch issued = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> buyer = pool.submit(() -> tx.executeWithoutResult(s -> {
                Event event = events.findById(eventId).orElseThrow();
                TicketTier tier = tiers.findById(TIER_A).orElseThrow();
                freeCheckoutService.issueFreeOrder(event, tier, 1, "race@example.test", null, false, false,
                        CheckoutAttribution.NONE, null);
                issued.countDown();
                await(release);
            }));
            assertThat(issued.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> organizer = pool.submit(() -> eventService.unpublish(principal, eventId));
            Thread.sleep(500);
            assertThat(organizer.isDone()).as("unpublish waits for the free order's tier lock").isFalse();

            release.countDown();
            buyer.get(10, TimeUnit.SECONDS);
            assertConflict(organizer, SOLD_MESSAGE);

            assertThat(eventStatus()).isEqualTo("LIVE");
            assertThat(tierInt(TIER_A, "sold")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static void assertConflict(Future<?> organizer, String message) {
        assertThatThrownBy(() -> organizer.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo(ErrorCode.INVALID_STATE);
                    assertThat(api.getMessage()).isEqualTo(message);
                });
    }

    private void insertTier(UUID id, int sortOrder) {
        jdbc.update("INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, reserved, "
                + "enabled, sort_order) VALUES (?, ?, 'Free', 0, 10, 0, 0, true, ?)", id, eventId, sortOrder);
    }

    private String eventStatus() {
        return jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, eventId);
    }

    private int tierInt(UUID tierId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM ticket_tiers WHERE id = ?", Integer.class, tierId);
    }

    private int heldReservations(UUID tierId) {
        return jdbc.queryForObject("SELECT count(*) FROM ticket_reservations WHERE tier_id = ? AND status = 'HELD'",
                Integer.class, tierId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(15, TimeUnit.SECONDS);
    }
}
