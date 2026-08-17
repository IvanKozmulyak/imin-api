package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.event.FreeCheckoutService;
import com.imin.iminapi.service.event.InventoryService;
import com.stripe.StripeClient;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.checkout.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code Idempotency-Key} on the free branch of
 * {@code POST /api/v1/public/events/{eventId}/checkout}.
 *
 * <p><b>What was broken.</b> The free branch finishes the purchase inside the
 * request: it reserves, confirms, writes an Order and writes N Tickets. Nothing
 * on the server made that call idempotent and the endpoint read no header, so
 * two calls produced two orders, two sets of tickets and two bites out of a real
 * capacity. It is also the branch where a buyer is most likely to tap twice: a
 * &euro;0 checkout raises no payment sheet, so nothing visible happens while it
 * works.
 *
 * <p><b>Why it mirrors {@link PaymentIntentIdempotencyTest} rather than
 * inventing its own answer.</b> The paid and free branches are the same promise
 * to the same client, so they must answer the same way to the same header: a
 * repeated key replays the first result without re-evaluating the request, an
 * absent key is unchanged behaviour, an over-long key is a 400, and a race is
 * settled by a unique index rather than by a lookup.
 *
 * <p>The mocked {@link FreeCheckoutService} here stands in for
 * {@code orders.idempotency_key} and {@code uq_orders_idem} (V94): the fake
 * remembers {@code (event, email, key) -> order} and throws
 * {@link DataIntegrityViolationException} when a second order tries to claim a
 * triple that is taken — the behaviour the index gives us on PostgreSQL and H2
 * alike. The <b>real</b> index, against the real engine and two real threads, is
 * proved in {@code FreeCheckoutConcurrencyTest} and
 * {@code V94OrdersIdempotencyKeyTest}; this class is about the semantics built
 * on top of it.
 */
class FreeCheckoutIdempotencyTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final String BUYER = "buyer@example.test";

    private final UUID eventId = UUID.randomUUID();
    private final UUID tierId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private SessionService sessionService;
    private EventRepository events;
    private TicketTierRepository tiers;
    private StripeConnectService connect;
    private InventoryService inventory;
    private FreeCheckoutService freeCheckout;
    private StripeCheckoutService svc;

    /** Stands in for uq_orders_idem: (event, normalized email, key) -> the order that claimed it. */
    private final Map<String, Order> claimed = new HashMap<>();

    /** When set, the next lookup of this triple answers "not found" — see the race tests. */
    private String blindTriple;

    private int nextOrder;

    @BeforeEach
    void setUp() throws Exception {
        StripeClient stripeClient = mock(StripeClient.class);
        CheckoutService checkout = mock(CheckoutService.class);
        sessionService = mock(SessionService.class);
        when(stripeClient.checkout()).thenReturn(checkout);
        when(checkout.sessions()).thenReturn(sessionService);

        events = mock(EventRepository.class);
        tiers = mock(TicketTierRepository.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        PromoCodeRepository promos = mock(PromoCodeRepository.class);
        connect = mock(StripeConnectService.class);
        inventory = mock(InventoryService.class);
        freeCheckout = mock(FreeCheckoutService.class);

        svc = new StripeCheckoutService(stripeClient, events, tiers, orgs, promos, connect,
                inventory, freeCheckout, new StripeProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        when(events.findPublic(eventId)).thenReturn(Optional.of(event()));
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(freeTier()));
        when(orgs.findById(orgId)).thenReturn(Optional.of(org()));

        // A distinct order per issuance, so a second issuance is impossible to miss.
        when(freeCheckout.issueFreeOrder(any(), any(), anyInt(), anyString(), nullable(com.imin.iminapi.model.PromoCode.class),
                anyBoolean(), anyBoolean(), any(), nullable(String.class), nullable(String.class)))
                .thenAnswer(inv -> {
                    String email = normalize(inv.getArgument(3));
                    String key = inv.getArgument(9);
                    Order order = new Order();
                    order.setId(UUID.randomUUID());
                    order.setToken("ord_" + (++nextOrder));
                    order.setEventId(eventId);
                    order.setEmail(email);
                    order.setIdempotencyKey(key);
                    if (key != null) {
                        // The unique index: the second claimant of a taken triple loses.
                        if (claimed.containsKey(triple(eventId, email, key))) {
                            throw new DataIntegrityViolationException("uq_orders_idem");
                        }
                        claimed.put(triple(eventId, email, key), order);
                    }
                    return order;
                });

        when(freeCheckout.findByIdempotencyKey(any(), nullable(String.class), nullable(String.class)))
                .thenAnswer(inv -> {
                    UUID event = inv.getArgument(0);
                    String email = normalize(inv.getArgument(1));
                    String key = inv.getArgument(2);
                    if (event == null || email == null || email.isEmpty() || key == null || key.isBlank()) {
                        return Optional.empty();
                    }
                    String triple = triple(event, email, key);
                    if (triple.equals(blindTriple)) {   // one-shot: models a lookup that lost the race
                        blindTriple = null;
                        return Optional.empty();
                    }
                    return Optional.ofNullable(claimed.get(triple));
                });

        when(freeCheckout.orderUrl(any()))
                .thenAnswer(inv -> "https://app.imin.test/order/" + inv.<Order>getArgument(0).getToken());
    }

    // ── The property the whole fix exists for ──────────────────────────────

    /**
     * The double tap. Without the replay the second call issues a second order,
     * a second set of tickets, and takes a second bite out of the capacity —
     * this test fails on {@code times(1)}.
     */
    @Test
    void sameKeyTwiceIsOneOrderAndOneAnswer() {
        var first = checkoutWith("key-abc");
        var second = checkoutWith("key-abc");

        assertThat(second).isEqualTo(first);
        assertThat(first.kind()).isEqualTo(StripeCheckoutService.CheckoutResult.KIND_ORDER);

        verify(freeCheckout, times(1)).issueFreeOrder(any(), any(), anyInt(), anyString(),
                nullable(com.imin.iminapi.model.PromoCode.class), anyBoolean(), anyBoolean(),
                any(), nullable(String.class), nullable(String.class));
    }

    /**
     * A replay must not be re-validated. The buyer's second tap can land after
     * the last seat went, and re-pricing would answer it with the leak-safe 404
     * — telling someone who already holds a valid order that the event does not
     * exist. Structurally guaranteed rather than merely observed: the key is
     * resolved before the tier is ever loaded.
     */
    @Test
    void replayIsNotRePricedWhenTheTierSellsOutBetweenTheTwoTaps() {
        var first = checkoutWith("key-abc");

        // Every remaining seat goes to somebody else; the tier is no longer buyable.
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.empty());

        var replay = checkoutWith("key-abc");

        assertThat(replay).isEqualTo(first);
        verify(tiers, times(1)).findByIdAndEventId(tierId, eventId);
    }

    /** The buyer's own order comes back, not a fresh one, even after the event is unpublished. */
    @Test
    void replayIsNotRePricedWhenTheEventIsWithdrawnBetweenTheTwoTaps() {
        var first = checkoutWith("key-abc");

        when(events.findPublic(eventId)).thenReturn(Optional.empty());

        assertThat(checkoutWith("key-abc")).isEqualTo(first);
    }

    // ── The header's three cases ───────────────────────────────────────────

    /**
     * imin-public sends no such header today and must be byte-identical to
     * before: two calls, two orders, and the idempotency machinery never
     * consulted at all.
     */
    @Test
    void noKeyIsUnchangedBehaviour() {
        var first = checkoutWith(null);
        var second = checkoutWith("   ");

        assertThat(second.orderToken()).isNotEqualTo(first.orderToken());
        verify(freeCheckout, times(2)).issueFreeOrder(any(), any(), anyInt(), anyString(),
                nullable(com.imin.iminapi.model.PromoCode.class), anyBoolean(), anyBoolean(),
                any(), nullable(String.class), nullable(String.class));
        verify(freeCheckout, never()).findByIdempotencyKey(any(), anyString(), anyString());
    }

    /**
     * Truncating to the column width would silently collapse two distinct keys
     * that share a prefix into one purchase — the exact failure this feature
     * exists to prevent, arrived at from the other direction. Letting it through
     * would instead overflow VARCHAR(128) at the INSERT and become a 500.
     */
    @Test
    void anOverlongKeyIsRejectedRatherThanTruncatedOr500() {
        assertThatThrownBy(() -> checkoutWith("k".repeat(129)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Idempotency-Key");

        verify(freeCheckout, never()).issueFreeOrder(any(), any(), anyInt(), anyString(),
                nullable(com.imin.iminapi.model.PromoCode.class), anyBoolean(), anyBoolean(),
                any(), nullable(String.class), nullable(String.class));
    }

    /** Exactly at the column width is a legitimate key, not an off-by-one rejection. */
    @Test
    void aKeyExactlyAtTheLimitIsAccepted() {
        String atLimit = "k".repeat(128);

        assertThat(checkoutWith(atLimit)).isEqualTo(checkoutWith(atLimit));
    }

    @Test
    void differentKeysAreDifferentPurchases() {
        var first = checkoutWith("key-abc");
        var second = checkoutWith("key-def");

        assertThat(second.orderToken()).isNotEqualTo(first.orderToken());
    }

    // ── The namespace is scoped, and that is a security property ───────────
    //
    // Deliberately NOT tested here. The scoping lives in `uq_orders_idem` and in
    // `FreeCheckoutService.findByIdempotencyKey`, both of which this class stands
    // in for with a fake — so a test written here would pass no matter what the
    // real index or the real query did. Two such tests existed and were deleted
    // after mutating the production code proved neither could go red. The property
    // is pinned instead against the real engine, in
    // FreeCheckoutConcurrencyTest.theSameKeyFromADifferentBuyerIsNotReplayedToThem,
    // .theSameBuyersAddressInADifferentCaseIsStillTheSameBuyer and
    // V94OrdersIdempotencyKeyTest.theSameKeyFromADifferentAddressIsNotADuplicate.

    // ── The race ───────────────────────────────────────────────────────────

    /**
     * Two taps in flight at once. Neither lookup can settle it — both read "no
     * such key" — so the unique index does. The loser must resolve to the
     * winner's order; swallowing the violation, or letting it out, is how a
     * duplicate becomes a 500.
     */
    @Test
    void aConcurrentDuplicateResolvesToTheWinnerRatherThan500() {
        var winner = checkoutWith("key-race");

        // The loser: a second in-flight tap whose lookup ran before the winner's
        // row was visible, so it went on to insert and only then hit the index.
        blindTriple = triple(eventId, normalize(BUYER), "key-race");

        assertThat(checkoutWith("key-race")).isEqualTo(winner);
    }

    /**
     * Whether Spring translates the conflict into a {@code DataIntegrityViolationException}
     * (the winner had committed) or a {@code ConcurrencyFailureException} (it had not
     * yet) is an engine-and-timing detail. Both are the same event and must produce the
     * same answer, or the fix works on one race and 500s on the other.
     */
    @Test
    void aConcurrentDuplicateReportedAsAConcurrencyFailureResolvesTheSameWay() {
        var winner = checkoutWith("key-race");

        when(freeCheckout.issueFreeOrder(any(), any(), anyInt(), anyString(),
                nullable(com.imin.iminapi.model.PromoCode.class), anyBoolean(), anyBoolean(),
                any(), nullable(String.class), nullable(String.class)))
                .thenThrow(new ConcurrencyFailureException("concurrent update on uq_orders_idem"));
        blindTriple = triple(eventId, normalize(BUYER), "key-race");

        assertThat(checkoutWith("key-race")).isEqualTo(winner);
    }

    /**
     * The index fired but the winning row cannot be read back. Do not invent an
     * order and do not hand back somebody else's: say the key is in flight, the
     * same 409 the native path gives when a key is claimed but its result has
     * not landed.
     */
    @Test
    void aDuplicateWithNoReadableWinnerIs409NotAFabricatedOrder() {
        when(freeCheckout.issueFreeOrder(any(), any(), anyInt(), anyString(),
                nullable(com.imin.iminapi.model.PromoCode.class), anyBoolean(), anyBoolean(),
                any(), nullable(String.class), nullable(String.class)))
                .thenThrow(new DataIntegrityViolationException("uq_orders_idem"));

        assertThatThrownBy(() -> checkoutWith("key-ghost"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("already in progress");
    }

    /**
     * An unkeyed request that trips some <i>other</i> constraint must not be
     * dressed up as an idempotent replay. There is no key to resolve it with, so
     * the failure is real and has to stay visible.
     */
    @Test
    void anUnkeyedConstraintViolationIsNotSwallowed() {
        when(freeCheckout.issueFreeOrder(any(), any(), anyInt(), anyString(),
                nullable(com.imin.iminapi.model.PromoCode.class), anyBoolean(), anyBoolean(),
                any(), nullable(String.class), nullable(String.class)))
                .thenThrow(new DataIntegrityViolationException("uq_orders_token"));

        assertThatThrownBy(() -> checkoutWith(null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── The paid branch is untouched ───────────────────────────────────────

    /**
     * A key on a paid checkout changes nothing about it: the hosted Session is
     * still created, and no order lookup stands between the buyer and Stripe.
     * The free branch is the one that needed this.
     */
    @Test
    void aKeyOnAPaidCheckoutStillCreatesAStripeSession() throws Exception {
        TicketTier paid = freeTier();
        paid.setPriceMinor(1500);
        paid.setStripePriceId("price_test");
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(paid));

        com.stripe.model.checkout.Session session = mock(com.stripe.model.checkout.Session.class);
        when(session.getUrl()).thenReturn("https://checkout.stripe.com/c/pay/cs_test");
        when(session.getId()).thenReturn("cs_test");
        when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);
        when(connect.getStatusLive(orgId)).thenReturn(new StripeConnectService.StatusResult(
                "acct_test", StripeConnectState.ACTIVE, true, true,
                java.util.List.of(), java.util.List.of(), null));
        when(inventory.reserve(any(), anyInt(), any(Instant.class), nullable(String.class)))
                .thenReturn(UUID.randomUUID());

        var result = checkoutWith("key-paid");

        assertThat(result.kind()).isEqualTo(StripeCheckoutService.CheckoutResult.KIND_STRIPE);
        verify(sessionService, times(1)).create(any(SessionCreateParams.class));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private StripeCheckoutService.CheckoutResult checkoutWith(String key) {
        return checkoutFor(BUYER, key);
    }

    private StripeCheckoutService.CheckoutResult checkoutFor(String email, String key) {
        return svc.createCheckout(eventId, tierId, 1, null, null, email,
                false, false, CheckoutAttribution.NONE, "en", key);
    }

    private static String triple(UUID event, String email, String key) {
        return event + "|" + email + "|" + key;
    }

    private static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    private Event event() {
        Event e = new Event();
        e.setId(eventId);
        e.setOrgId(orgId);
        e.setName("Free Fest");
        e.setSlug("free-fest");
        e.setStatus(EventStatus.LIVE);
        e.setVisibility(EventVisibility.PUBLIC);
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setCurrency("EUR");
        return e;
    }

    private TicketTier freeTier() {
        TicketTier t = new TicketTier();
        t.setId(tierId);
        t.setEventId(eventId);
        t.setName("Free GA");
        t.setPriceMinor(0);
        t.setQuantity(100);
        t.setReserved(0);
        t.setSold(0);
        t.setEnabled(true);
        return t;
    }

    private Organization org() {
        Organization o = new Organization();
        o.setId(orgId);
        o.setName("Free Org");
        o.setContactEmail("o@example.test");
        o.setCountry("DE");
        o.setStripeAccountId("acct_test");
        return o;
    }
}
