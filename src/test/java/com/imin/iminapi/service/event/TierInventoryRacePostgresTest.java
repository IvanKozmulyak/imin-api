package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.dto.event.TicketTierCreateRequest;
import com.imin.iminapi.dto.event.TicketTierEmbeddedPatch;
import com.imin.iminapi.dto.event.TicketTierPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminIntegrationTest;
import com.stripe.StripeClient;
import com.stripe.param.ProductCreateParams;
import com.stripe.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Organizer tier writes racing checkout inventory on one tier row, on Postgres (READ COMMITTED). */
@IminIntegrationTest
class TierInventoryRacePostgresTest {

    // Sign bit clear vs set: Postgres sorts tierA first, UUID.compareTo sorts tierB first.
    private final UUID tierA = new UUID(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE,
            UUID.randomUUID().getLeastSignificantBits());
    private final UUID tierB = new UUID(UUID.randomUUID().getMostSignificantBits() | Long.MIN_VALUE,
            UUID.randomUUID().getLeastSignificantBits());

    @Autowired StripeClient stripeClient;

    @Autowired EventService eventService;
    @Autowired TicketTierService tierService;
    @Autowired InventoryService inventoryService;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private UUID orgId;
    private AuthPrincipal principal;
    private UUID eventId;
    private UUID tierId;
    /** When set, the Stripe product create signals {@link #syncEntered} and blocks on it: the organizer's pause point. */
    private volatile CountDownLatch syncGate;
    private final CountDownLatch syncEntered = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        Organization o = new Organization();
        o.setName("Tier race");
        o.setSlug("tir-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("tir@example.test");
        o.setCountry("DE");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("tir-" + UUID.randomUUID() + "@example.test");
        u.setFirstName("Ada");
        u.setOrgId(orgId);
        u.setRole(UserRole.MEMBER);
        principal = new AuthPrincipal(users.save(u).getId(), orgId, UserRole.MEMBER, UUID.randomUUID());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(principal.userId());
        e.setName("Night");
        e.setSlug("tir-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setCurrency("EUR");
        e.setStartsAt(now.plus(10, ChronoUnit.DAYS));
        e.setEndsAt(now.plus(10, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS));
        e.setPublishedAt(now.minus(2, ChronoUnit.DAYS));
        eventId = events.save(e).getId();

        tierId = insertTier(UUID.randomUUID(), 10, 0);

        // The sync runs after commit on the sync executor, so the gate keys on the latch, not the thread.
        ProductService productService = mock(ProductService.class);
        when(stripeClient.products()).thenReturn(productService);
        doAnswer(inv -> {
            CountDownLatch gate = syncGate;
            if (gate != null) {
                syncEntered.countDown();
                await(gate);
            }
            return null;
        }).when(productService).create(any(ProductCreateParams.class));
    }

    @AfterEach
    void tearDown() {
        String tiersOfOrg = "SELECT t.id FROM ticket_tiers t JOIN events e ON e.id = t.event_id WHERE e.org_id = ?";
        jdbc.update("DELETE FROM ticket_reservations WHERE tier_id IN (" + tiersOfOrg + ")", orgId);
        jdbc.update("DELETE FROM ticket_tier_milestones WHERE tier_id IN (" + tiersOfOrg + ")", orgId);
        jdbc.update("DELETE FROM audit_logs WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM ticket_tiers WHERE event_id IN (SELECT id FROM events WHERE org_id = ?)", orgId);
        jdbc.update("DELETE FROM events WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM users WHERE org_id = ?", orgId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    // ── standalone tier patch ──────────────────────────────────────────────────

    @Test
    @Timeout(60)
    void tierPatch_organizerHoldsTier_reserveWaitsAndItsHoldSurvives() throws Exception {
        organizerHoldsTierThenReserve(() -> tierService.patch(principal, eventId, tierId, rename()));
    }

    @Test
    @Timeout(60)
    void tierPatch_reserveHoldsTier_organizerWaitsAndKeepsTheHold() throws Exception {
        reserveHoldsTierThenOrganizer(() -> tierService.patch(principal, eventId, tierId, rename()));
    }

    @Test
    @Timeout(60)
    void tierPatch_quantityCutBelowConcurrentHold_refusedWithLockedCount() throws Exception {
        quantityCutBelowConcurrentHold(() -> tierService.patch(principal, eventId, tierId,
                new TicketTierPatchRequest(null, null, 8, null, null, null, null, null, null)));
    }

    // ── embedded tier patch through EventService.patch ─────────────────────────

    @Test
    @Timeout(60)
    void embeddedPatch_organizerHoldsTier_reserveWaitsAndItsHoldSurvives() throws Exception {
        organizerHoldsTierThenReserve(() -> eventService.patch(principal, eventId, null,
                eventPatch(null, List.of(embedded(tierId, "Renamed", null)))));
    }

    @Test
    @Timeout(60)
    void embeddedPatch_reserveHoldsTier_organizerWaitsAndKeepsTheHold() throws Exception {
        reserveHoldsTierThenOrganizer(() -> eventService.patch(principal, eventId, null,
                eventPatch(null, List.of(embedded(tierId, "Renamed", null)))));
    }

    @Test
    @Timeout(60)
    void embeddedPatch_quantityCutBelowConcurrentHold_refused() throws Exception {
        quantityCutBelowConcurrentHold(() -> eventService.patch(principal, eventId, null,
                eventPatch(null, List.of(embedded(tierId, null, 8)))));
    }

    // ── Stripe sync after commit ───────────────────────────────────────────────

    @Test
    @Timeout(60)
    void tierPatch_stripeSyncBlocked_tierAndEventLocksFree() throws Exception {
        stripeBlockedLocksFree(() -> tierService.patch(principal, eventId, tierId, rename()), tierId);
        assertThat(nameOf(tierId)).isEqualTo("Renamed");
    }

    @Test
    @Timeout(60)
    void embeddedPatch_stripeSyncBlocked_tierAndEventLocksFree() throws Exception {
        stripeBlockedLocksFree(() -> eventService.patch(principal, eventId, null,
                eventPatch(null, List.of(embedded(tierId, "Renamed", null)))), tierId);
        assertThat(nameOf(tierId)).isEqualTo("Renamed");
    }

    @Test
    @Timeout(60)
    void tierCreate_stripeSyncBlocked_eventLockFree() throws Exception {
        stripeBlockedLocksFree(() -> tierService.create(principal, eventId,
                new TicketTierCreateRequest("Door", 1500, 20, null, null, null, null)), null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_tiers WHERE event_id = ? AND name = 'Door'",
                Integer.class, eventId)).isEqualTo(1);
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Test
    @Timeout(60)
    void tierDelete_organizerHoldsTier_reserveFindsNoTierAndNoHoldIsLeft() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> organizer = pool.submit(() -> tx.executeWithoutResult(s -> {
                tierService.delete(principal, eventId, tierId);
                deleted.countDown();
                await(release);
            }));
            assertThat(deleted.await(15, TimeUnit.SECONDS)).isTrue();

            Future<UUID> buyer = pool.submit(this::reserveTwo);
            Thread.sleep(500);
            assertThat(buyer.isDone()).as("reserve waits for the organizer's tier lock").isFalse();

            release.countDown();
            organizer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> buyer.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));

            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_reservations WHERE tier_id = ?",
                    Integer.class, tierId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_tiers WHERE id = ?",
                    Integer.class, tierId)).isZero();
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    @Test
    @Timeout(60)
    void tierDelete_reserveHoldsTier_deleteRefused409() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> buyer = pool.submit(() -> tx.executeWithoutResult(s -> {
                reserveTwo();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> organizer = pool.submit(() -> tierService.delete(principal, eventId, tierId));
            Thread.sleep(500);
            assertThat(organizer.isDone()).as("delete waits for the buyer's tier lock").isFalse();

            release.countDown();
            buyer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> organizer.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(api.code()).isEqualTo(ErrorCode.INVALID_STATE);
                        assertThat(api.getMessage()).isEqualTo("Cannot delete a tier with checkouts in progress "
                                + "— disable it instead (set enabled=false)");
                    });

            Map<String, Object> row = tierRow();
            assertThat(row.get("reserved")).isEqualTo(2);
            assertThat(heldReservations()).isEqualTo(1);
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    // ── lock ordering ──────────────────────────────────────────────────────────

    @Test
    @Timeout(60)
    void embeddedPatchOfTwoTiers_lockedInRefundOrder_noDeadlock() throws Exception {
        insertTier(tierA, 10, 0);
        insertTier(tierB, 10, 0);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Same order as RefundService: UUID.compareTo puts tierB first.
            Future<?> refundLike = pool.submit(() -> tx.executeWithoutResult(s -> {
                tiers.findByIdForUpdate(tierB).orElseThrow();
                locked.countDown();
                await(release);
                tiers.findByIdForUpdate(tierA).orElseThrow();
            }));
            assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> organizer = pool.submit(() -> eventService.patch(principal, eventId, null,
                    eventPatch(null, List.of(embedded(tierA, "A2", null), embedded(tierB, "B2", null)))));
            Thread.sleep(500);
            release.countDown();

            refundLike.get(10, TimeUnit.SECONDS);
            organizer.get(10, TimeUnit.SECONDS);
            assertThat(nameOf(tierA)).isEqualTo("A2");
            assertThat(nameOf(tierB)).isEqualTo("B2");
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    @Test
    @Timeout(60)
    void slugChangeWithTierPatch_vsCheckoutHoldingTier_noDeadlock() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String newSlug = "new-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // A webhook confirming a sale: tier lock, then the orders.event_id FK check on the event.
            Future<?> checkout = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT id FROM ticket_tiers WHERE id = ? FOR UPDATE", UUID.class, tierId);
                locked.countDown();
                await(release);
                jdbc.queryForObject("SELECT id FROM events WHERE id = ? FOR KEY SHARE", UUID.class, eventId);
            }));
            assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> organizer = pool.submit(() -> eventService.patch(principal, eventId, null,
                    eventPatch(newSlug, List.of(embedded(tierId, "Renamed", null)))));
            Thread.sleep(500);
            release.countDown();

            checkout.get(10, TimeUnit.SECONDS);
            organizer.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT slug FROM events WHERE id = ?", String.class, eventId))
                    .isEqualTo(newSlug);
            assertThat(nameOf(tierId)).isEqualTo("Renamed");
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    // ── shared scenarios ───────────────────────────────────────────────────────

    /** The organizer pauses after its write, still holding the tier; a reserve must wait and its hold must survive. */
    private void organizerHoldsTierThenReserve(Callable<?> organizerWrite) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<?> organizer = pool.submit(() -> tx.executeWithoutResult(s -> {
                try {
                    organizerWrite.call();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                syncEntered.countDown();
                await(gate);
            }));
            assertThat(syncEntered.await(15, TimeUnit.SECONDS)).isTrue();

            Future<UUID> buyer = pool.submit(this::reserveTwo);
            Thread.sleep(500);
            assertThat(buyer.isDone()).as("reserve waits for the organizer's tier lock").isFalse();

            gate.countDown();
            organizer.get(10, TimeUnit.SECONDS);
            buyer.get(10, TimeUnit.SECONDS);

            Map<String, Object> row = tierRow();
            assertThat(row.get("reserved")).isEqualTo(2);
            assertThat(row.get("name")).isEqualTo("Renamed");
            assertThat(heldReservations()).isEqualTo(1);
        } finally {
            gate.countDown();
            shutdown(pool);
        }
    }

    /** Stripe blocks after the organizer's commit; the write returns and neither the tier nor the event stays locked. */
    private void stripeBlockedLocksFree(Callable<?> organizerWrite, UUID lockedTier) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        syncGate = gate;
        try {
            Future<?> organizer = pool.submit(organizerWrite);
            organizer.get(5, TimeUnit.SECONDS);
            assertThat(syncEntered.await(15, TimeUnit.SECONDS)).as("the queued sync reached Stripe").isTrue();

            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(s -> {
                if (lockedTier != null) {
                    jdbc.queryForObject("SELECT id FROM ticket_tiers WHERE id = ? FOR UPDATE NOWAIT",
                            UUID.class, lockedTier);
                }
                jdbc.queryForObject("SELECT id FROM events WHERE id = ? FOR UPDATE NOWAIT", UUID.class, eventId);
            });
            Future<UUID> buyer = pool.submit(this::reserveTwo);
            assertThat(buyer.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(heldReservations()).isEqualTo(1);
        } finally {
            gate.countDown();
            syncGate = null;
            shutdown(pool);
        }
    }

    /** A buyer holds the tier with an uncommitted reserve; the organizer must wait and keep the hold. */
    private void reserveHoldsTierThenOrganizer(Callable<?> organizerWrite) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> buyer = pool.submit(() -> tx.executeWithoutResult(s -> {
                reserveTwo();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> organizer = pool.submit(organizerWrite);
            Thread.sleep(500);
            assertThat(organizer.isDone()).as("the organizer waits for the buyer's tier lock").isFalse();

            release.countDown();
            buyer.get(10, TimeUnit.SECONDS);
            organizer.get(10, TimeUnit.SECONDS);

            Map<String, Object> row = tierRow();
            assertThat(row.get("reserved")).isEqualTo(2);
            assertThat(row.get("name")).isEqualTo("Renamed");
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    /** Sold 7 plus an uncommitted hold of 2: a cut to 8 must be refused against the locked count of 9. */
    private void quantityCutBelowConcurrentHold(Callable<?> organizerWrite) throws Exception {
        jdbc.update("UPDATE ticket_tiers SET sold = 7 WHERE id = ?", tierId);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> buyer = pool.submit(() -> tx.executeWithoutResult(s -> {
                reserveTwo();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> organizer = pool.submit(organizerWrite);
            Thread.sleep(500);
            release.countDown();
            buyer.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> organizer.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                        assertThat(api.fields())
                                .containsEntry("quantity", "must be ≥ sold + checkouts in progress (9)");
                    });

            Map<String, Object> row = tierRow();
            assertThat(row.get("quantity")).isEqualTo(10);
            assertThat(row.get("sold")).isEqualTo(7);
            assertThat(row.get("reserved")).isEqualTo(2);
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID insertTier(UUID id, int quantity, int sortOrder) {
        jdbc.update("INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, reserved, "
                + "enabled, sort_order) VALUES (?, ?, 'Free', 0, ?, 0, 0, true, ?)", id, eventId, quantity, sortOrder);
        return id;
    }

    private UUID reserveTwo() {
        return inventoryService.reserve(tierId, 2, Instant.now().plus(30, ChronoUnit.MINUTES), null);
    }

    private static TicketTierPatchRequest rename() {
        return new TicketTierPatchRequest("Renamed", null, null, null, null, null, null, null, null);
    }

    private static TicketTierEmbeddedPatch embedded(UUID id, String name, Integer quantity) {
        return new TicketTierEmbeddedPatch(id, name, null, quantity, null, null, null, null, null, null);
    }

    private static EventPatchRequest eventPatch(String slug, List<TicketTierEmbeddedPatch> tierPatches) {
        return new EventPatchRequest(null, slug, null, null, null, null, null, null, null,
                null, null, null, null, null, null, tierPatches, null);
    }

    private Map<String, Object> tierRow() {
        return jdbc.queryForMap("SELECT name, quantity, sold, reserved FROM ticket_tiers WHERE id = ?", tierId);
    }

    private String nameOf(UUID id) {
        return jdbc.queryForObject("SELECT name FROM ticket_tiers WHERE id = ?", String.class, id);
    }

    private int heldReservations() {
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
