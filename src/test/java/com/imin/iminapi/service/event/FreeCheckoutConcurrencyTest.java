package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketReservationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.stripe.StripeCheckoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The live defect, against the real database.
 *
 * <p>A free ticket could be claimed twice.
 * {@code POST /api/v1/public/events/{eventId}/checkout} read no
 * {@code Idempotency-Key} and {@link FreeCheckoutService#issueFreeOrder} was not
 * idempotent, so two calls produced two orders, two sets of tickets and two
 * bites out of an event's real capacity. Nothing on the server prevented it, and
 * the free path is exactly where a buyer double-taps: a &euro;0 checkout raises
 * no payment sheet, so nothing visible happens while the request works.
 *
 * <p>{@code FreeCheckoutIdempotencyTest} covers the semantics against a mocked
 * index. This class is deliberately the opposite: real H2 in PostgreSQL-compat
 * mode, the real {@code uq_orders_idem}, the real transaction boundaries and
 * real threads — because the interesting failure is a race, and a race cannot be
 * proved against a HashMap.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class FreeCheckoutConcurrencyTest {

    private static final String BUYER = "double.tapper@example.test";
    private static final int QUANTITY = 2;

    @Autowired StripeCheckoutService checkout;
    @Autowired FreeCheckoutService freeCheckout;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired TicketTierRepository tiers;
    @Autowired TicketReservationRepository reservations;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private Event event;
    private TicketTier freeTier;

    @BeforeEach
    void setUp() {
        cleanUp();
        event = seedEvent("Free Fest");
        freeTier = seedFreeTier(event, 100);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ── The test that matters most ─────────────────────────────────────────

    /**
     * Two simultaneous requests carrying the same {@code Idempotency-Key}.
     *
     * <p>Neither can see the other's row when it looks the key up, so the lookup
     * cannot settle this — the unique index has to, and the loser has to resolve
     * to the winner rather than surface the violation as a 500. Exactly one
     * order, exactly {@code QUANTITY} tickets, exactly {@code QUANTITY} seats
     * off the tier, and both callers get the same order token.
     *
     * <p>Without the fix this fails on the very first assertion: two orders.
     */
    @Test
    void twoConcurrentCallsWithTheSameKeyProduceExactlyOneOrderAndOneSetOfTickets() throws Exception {
        List<StripeCheckoutService.CheckoutResult> answers = raceTwoCheckouts("the-same-key");

        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId()))
                .as("one tap, one order — a second one is a free ticket claimed twice")
                .hasSize(1);

        Order theOrder = orders.findByEventIdOrderByCreatedAtDesc(event.getId()).get(0);
        assertThat(tickets.findByOrderIdOrderByCreatedAtAsc(theOrder.getId()))
                .as("tickets issued")
                .hasSize(QUANTITY);
        assertThat(tickets.findAll())
                .as("no orphan tickets from the rolled-back loser")
                .hasSize(QUANTITY);

        assertThat(answers)
                .as("both callers were told about the same order")
                .allSatisfy(r -> {
                    assertThat(r.kind()).isEqualTo(StripeCheckoutService.CheckoutResult.KIND_ORDER);
                    assertThat(r.orderToken()).isEqualTo(theOrder.getToken());
                });

        TicketTier reloaded = tiers.findById(freeTier.getId()).orElseThrow();
        assertThat(reloaded.getSold()).as("seats sold against real capacity").isEqualTo(QUANTITY);
        assertThat(reloaded.getReserved()).as("the loser's hold went back to the pool").isZero();
    }

    /**
     * The defect itself, pinned so it cannot come back by another road: with no
     * key there is nothing to deduplicate on, and two calls really do produce two
     * orders. This is the behaviour every shipped web client still has, and the
     * reason an absent header must stay unkeyed rather than become an error.
     */
    @Test
    void twoCallsWithoutAKeyStillProduceTwoOrders() throws Exception {
        raceTwoCheckouts(null);

        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId())).hasSize(2);
        assertThat(tiers.findById(freeTier.getId()).orElseThrow().getSold()).isEqualTo(2 * QUANTITY);
    }

    // ── The index, and the rollback that makes the loser harmless ──────────

    /**
     * The database, not the application, is what makes this safe. Called
     * directly — no lookup in front of it — a second {@code issueFreeOrder} with
     * a key that is taken must be rejected by {@code uq_orders_idem}.
     *
     * <p>And the rejection must cost nothing: {@code issueFreeOrder} is one
     * transaction, so the loser's reserve + confirmSold roll back with its order.
     * That is why there is no compensating release anywhere in this fix — a
     * compensating write can itself fail, a rollback cannot.
     */
    @Test
    void theIndexRejectsASecondOrderForTheSameKeyAndTheLoserCostsNoInventory() {
        freeCheckout.issueFreeOrder(event, freeTier, QUANTITY, BUYER, null, false, false,
                CheckoutAttribution.NONE, "en", "taken-key");

        assertThatThrownBy(() -> freeCheckout.issueFreeOrder(event, freeTier, QUANTITY, BUYER, null,
                false, false, CheckoutAttribution.NONE, "en", "taken-key"))
                .isInstanceOf(DataAccessException.class);

        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId())).hasSize(1);
        assertThat(tickets.findAll()).hasSize(QUANTITY);
        TicketTier reloaded = tiers.findById(freeTier.getId()).orElseThrow();
        assertThat(reloaded.getSold()).isEqualTo(QUANTITY);
        assertThat(reloaded.getReserved()).isZero();
        assertThat(reservations.findAll())
                .as("the loser's HELD row rolled back too — nothing for the sweeper to find")
                .hasSize(1);
    }

    /**
     * NULLs are distinct in a unique index on PostgreSQL and H2 alike, which is
     * the whole reason unkeyed callers are unaffected. If this ever stopped being
     * true, the second unkeyed free order on an event would start failing for
     * every buyer who had already bought one.
     */
    @Test
    void unkeyedOrdersDoNotCollideWithEachOther() {
        freeCheckout.issueFreeOrder(event, freeTier, 1, BUYER, null, false, false,
                CheckoutAttribution.NONE, "en", null);
        freeCheckout.issueFreeOrder(event, freeTier, 1, BUYER, null, false, false,
                CheckoutAttribution.NONE, "en", null);

        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId())).hasSize(2);
    }

    // ── The namespace is scoped, against the real index ────────────────────

    /**
     * The key is minted by an untrusted client and a replay hands back
     * {@code orders.token} — the bearer credential for the buyer's tickets. If
     * the index were global, this second call would be rejected, and the buyer
     * would be handed a stranger's order instead of their own ticket. Two
     * different buyers with the same key is not a duplicate.
     */
    @Test
    void theSameKeyFromADifferentBuyerIsItsOwnOrder() {
        Order mine = freeCheckout.issueFreeOrder(event, freeTier, 1, BUYER, null, false, false,
                CheckoutAttribution.NONE, "en", "guessable");
        Order theirs = freeCheckout.issueFreeOrder(event, freeTier, 1, "someone.else@example.test",
                null, false, false, CheckoutAttribution.NONE, "en", "guessable");

        assertThat(theirs.getToken()).isNotEqualTo(mine.getToken());
        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId())).hasSize(2);
    }

    /** The same buyer with the same key on a different event is a different purchase. */
    @Test
    void theSameKeyOnADifferentEventIsItsOwnOrder() {
        Event other = seedEvent("Other Fest");
        TicketTier otherTier = seedFreeTier(other, 100);

        freeCheckout.issueFreeOrder(event, freeTier, 1, BUYER, null, false, false,
                CheckoutAttribution.NONE, "en", "shared-key");
        freeCheckout.issueFreeOrder(other, otherTier, 1, BUYER, null, false, false,
                CheckoutAttribution.NONE, "en", "shared-key");

        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId())).hasSize(1);
        assertThat(orders.findByEventIdOrderByCreatedAtDesc(other.getId())).hasSize(1);
    }

    /**
     * The same scoping, but through {@code createCheckout} rather than straight
     * at the writer — because the replay <b>lookup</b> has its own copy of the
     * namespace and a lookup that ignored the address would hand the second
     * buyer the first buyer's order token even while the index behaved
     * perfectly. The constraint and the query have to agree, so both are pinned.
     */
    @Test
    void theSameKeyFromADifferentBuyerIsNotReplayedToThem() {
        var mine = checkoutAs(BUYER, "guessable");
        var theirs = checkoutAs("attacker@example.test", "guessable");

        assertThat(theirs.orderToken())
                .as("a guessed key must never hand over somebody else's ticket page")
                .isNotEqualTo(mine.orderToken());
        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId())).hasSize(2);
    }

    /**
     * The other half of that: the address is normalized before it is compared,
     * so a buyer who retries from a form that capitalised their address is still
     * the same buyer and still gets one order — not a second free ticket.
     */
    @Test
    void theSameBuyersAddressInADifferentCaseIsStillTheSameBuyer() {
        var first = checkoutAs("Double.Tapper@Example.TEST", "case-key");
        var second = checkoutAs("  double.tapper@example.test  ", "case-key");

        assertThat(second.orderToken()).isEqualTo(first.orderToken());
        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId())).hasSize(1);
    }

    // ── The sequential retry, end to end ───────────────────────────────────

    /**
     * The ordinary case: the buyer's second tap lands after the first finished.
     * The lookup catches it before anything is priced or reserved, so no seats
     * move and the same order comes back.
     */
    @Test
    void aSequentialRetryWithTheSameKeyReturnsTheSameOrderAndTakesNoMoreSeats() {
        var first = checkoutOnce("retry-key");
        var second = checkoutOnce("retry-key");

        assertThat(second.orderToken()).isEqualTo(first.orderToken());
        assertThat(orders.findByEventIdOrderByCreatedAtDesc(event.getId())).hasSize(1);
        assertThat(tiers.findById(freeTier.getId()).orElseThrow().getSold()).isEqualTo(QUANTITY);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private StripeCheckoutService.CheckoutResult checkoutOnce(String key) {
        return checkoutAs(BUYER, key);
    }

    private StripeCheckoutService.CheckoutResult checkoutAs(String email, String key) {
        return checkout.createCheckout(event.getId(), freeTier.getId(), QUANTITY, null, 0, email,
                false, false, CheckoutAttribution.NONE, "en", key);
    }

    /** Both threads park on one latch so they stampede the endpoint together. */
    private List<StripeCheckoutService.CheckoutResult> raceTwoCheckouts(String key) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<StripeCheckoutService.CheckoutResult> results = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        results.add(checkoutOnce(key));
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("both taps queued").isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("both taps finished").isTrue();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(failures)
                .as("a duplicate must never reach the buyer as an error — that is the 500 this fix exists to avoid")
                .isEmpty();
        assertThat(results).hasSize(2);
        return results;
    }

    private Event seedEvent(String name) {
        Organization org = new Organization();
        org.setName(name + " Org");
        org.setSlug("free-idem-org-" + UUID.randomUUID());
        org.setContactEmail("free-idem-" + UUID.randomUUID() + "@example.test");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("free-idem-owner-" + UUID.randomUUID() + "@example.test");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName(name);
        e.setSlug("free-idem-event-" + UUID.randomUUID());
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minusSeconds(3600));
        e.setStartsAt(Instant.now().plusSeconds(7 * 24 * 3600));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return events.save(e);
    }

    private TicketTier seedFreeTier(Event on, int capacity) {
        TicketTier t = new TicketTier();
        t.setEventId(on.getId());
        t.setName("Free GA");
        t.setPriceMinor(0);
        t.setQuantity(capacity);
        t.setReserved(0);
        t.setSold(0);
        t.setEnabled(true);
        return tiers.save(t);
    }

    private void cleanUp() {
        tickets.deleteAll();
        orders.deleteAll();
        reservations.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }
}
