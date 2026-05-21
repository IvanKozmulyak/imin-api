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
import com.imin.iminapi.repository.TicketReservationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency proof for {@link InventoryService#reserve(UUID, int)}.
 *
 * <p>Models the goal scenario: 100 buyers race for the last 50 seats of a tier.
 * The pessimistic row lock on {@code ticket_tiers} (via {@code SELECT … FOR UPDATE}
 * in {@link TicketTierRepository#findByIdForUpdate(UUID)}) must serialize the
 * availability check + decrement so exactly 50 reservations succeed and 50 see
 * a sold-out error — never 51, never 49.
 *
 * <p>H2 in PG-compat mode (the default test DB; see {@code src/test/resources/application.yaml})
 * implements {@code FOR UPDATE} as a row-level lock with the same wait-on-conflict
 * semantics as Postgres for this kind of single-row contention, so the assertion
 * also holds under the real DB.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class InventoryConcurrencyTest {

    @Autowired InventoryService inventory;
    @Autowired TicketTierRepository tiers;
    @Autowired TicketReservationRepository reservations;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private TicketTier tier;
    private Event event;
    private Organization org;
    private User owner;

    @BeforeEach
    void setUp() {
        reservations.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();

        org = new Organization();
        org.setName("Concurrency Org");
        org.setSlug("concurrency-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("concurrency@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        owner = new User();
        owner.setEmail("concurrency-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
    }

    @AfterEach
    void tearDown() {
        reservations.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private TicketTier seedTier(int capacity) {
        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Concurrency Event");
        event.setSlug("concurrency-event-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setPublishedAt(Instant.now().minusSeconds(3600));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        TicketTier t = new TicketTier();
        t.setEventId(event.getId());
        t.setName("GA");
        t.setPriceMinor(1000);
        t.setQuantity(capacity);
        t.setReserved(0);
        t.setSold(0);
        t.setEnabled(true);
        return tiers.save(t);
    }

    @Test
    void hundredConcurrentBuyers_against50Capacity_reserveExactly50() throws Exception {
        final int capacity = 50;
        final int buyers = 100;
        tier = seedTier(capacity);

        // One thread per buyer so they can all block on the start latch
        // simultaneously — a smaller pool deadlocks because every worker has
        // already counted down and is parked on start.await().
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch ready = new CountDownLatch(buyers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(buyers);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try {
            for (int i = 0; i < buyers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        // Wait for the green light so the buyers stampede the row
                        // at roughly the same wall-clock moment.
                        start.await();
                        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(30));
                        inventory.reserve(tier.getId(), 1, expiresAt, null);
                        successes.incrementAndGet();
                    } catch (ApiException ex) {
                        if (ex.code() == ErrorCode.INVALID_STATE) soldOut.incrementAndGet();
                        else other.incrementAndGet();
                    } catch (Exception ex) {
                        other.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("all buyer threads queued")
                    .isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("all buyer threads finished within 30s")
                    .isTrue();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(successes.get()).as("reservations that succeeded").isEqualTo(capacity);
        assertThat(soldOut.get()).as("buyers that hit SOLD_OUT").isEqualTo(buyers - capacity);
        assertThat(other.get()).as("unexpected failures").isZero();

        TicketTier reloaded = tiers.findById(tier.getId()).orElseThrow();
        assertThat(reloaded.getReserved()).as("final reserved count").isEqualTo(capacity);
        assertThat(reloaded.getSold()).as("final sold count").isZero();
    }
}
