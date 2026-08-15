package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.ReservationStatus;
import com.imin.iminapi.model.TicketReservation;
import com.imin.iminapi.security.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code Idempotency-Key} on the native PaymentIntent endpoint.
 *
 * <p><b>Why this is a launch blocker rather than a nicety.</b>
 * {@code InventoryService.reserve} writes a new hold on every call, before
 * Stripe is touched. The web never needed protection because its checkout was a
 * browser navigation; a native client on a flaky link loses the response and
 * retries, and each retry would hold real seats on the hot tier for 30 minutes.
 * A shipped binary cannot be force-updated, so adding this later protects only
 * newer installs — the launch cohort keeps the duplicate-hold behaviour forever.
 *
 * <p>The mocked {@link com.imin.iminapi.service.event.InventoryService} here
 * stands in for the {@code idempotency_key} column and its unique index: the
 * fake claims a key, remembers it, and throws
 * {@link DataIntegrityViolationException} when a second reservation tries to
 * claim the same one — the behaviour {@code uq_ticket_reservations_idem} gives
 * us in Postgres and H2 alike.
 */
class PaymentIntentIdempotencyTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID TIER = UUID.randomUUID();

    private com.stripe.StripeClient stripeClient;
    private com.stripe.service.PaymentIntentService paymentIntents;
    private StripeCheckoutService checkoutService;
    private com.imin.iminapi.service.event.InventoryService inventory;
    private StripePaymentIntentService service;

    /** Stands in for the unique index: key → the reservation that claimed it. */
    private final Map<String, TicketReservation> claimed = new HashMap<>();

    /** Every reservation the stubbed prelude has handed out, by id. */
    private final Map<UUID, TicketReservation> rows = new HashMap<>();

    private int nextIntent;

    /** When set, the next lookup of this key answers "not found" — see the race test. */
    private String blindKey;

    private void blindNextLookup(String key) {
        this.blindKey = key;
    }

    @BeforeEach
    void setUp() throws Exception {
        stripeClient = mock(com.stripe.StripeClient.class);
        paymentIntents = mock(com.stripe.service.PaymentIntentService.class);
        when(stripeClient.paymentIntents()).thenReturn(paymentIntents);
        checkoutService = mock(StripeCheckoutService.class);
        inventory = mock(com.imin.iminapi.service.event.InventoryService.class);
        service = new StripePaymentIntentService(stripeClient, checkoutService, inventory);

        // A distinct intent per create, so a second create is impossible to miss.
        when(paymentIntents.create(any(PaymentIntentCreateParams.class))).thenAnswer(inv -> {
            PaymentIntentCreateParams sent = inv.getArgument(0);
            String id = "pi_test_" + (++nextIntent);
            PaymentIntent created = new PaymentIntent();
            created.setId(id);
            created.setClientSecret(id + "_secret_abc");
            created.setAmount(sent.getAmount());
            created.setApplicationFeeAmount(sent.getApplicationFeeAmount());
            created.setCurrency(sent.getCurrency());
            // Stripe remembers the intent; a later retrieve reads it back
            // verbatim, which is what makes the replayed amount the STORED one.
            when(paymentIntents.retrieve(id)).thenReturn(created);
            return created;
        });

        when(inventory.findByIdempotencyKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.equals(blindKey)) {   // one-shot: models a lookup that lost the race
                blindKey = null;
                return Optional.empty();
            }
            return Optional.ofNullable(claimed.get(key));
        });

        doAnswer(inv -> {
            UUID reservationId = inv.getArgument(0);
            String key = inv.getArgument(1);
            TicketReservation existing = claimed.get(key);
            if (existing != null && !existing.getId().equals(reservationId)) {
                throw new DataIntegrityViolationException("uq_ticket_reservations_idem");
            }
            TicketReservation row = rows.get(reservationId);
            row.setIdempotencyKey(key);
            claimed.put(key, row);
            return null;
        }).when(inventory).claimIdempotencyKey(any(), anyString());

        doAnswer(inv -> {
            rows.get(inv.<UUID>getArgument(0)).setStripeSessionId(inv.getArgument(1));
            return null;
        }).when(inventory).attachSessionId(any(), anyString());

        stubPrelude(5000L, 448L);
    }

    /**
     * The property the whole feature exists for. Without the replay, the second
     * call reserves again and creates a second PaymentIntent — this test fails
     * on {@code times(1)} for both.
     */
    @Test
    void sameKeyTwiceIsOneHoldOneIntentAndOneAnswer() throws Exception {
        var first = create("key-abc");
        var second = create("key-abc");

        assertThat(second).isEqualTo(first);

        verify(paymentIntents, times(1)).create(any(PaymentIntentCreateParams.class));
        verify(checkoutService, times(1)).reserveAndBuildMetadata(
                any(), any(), any(), anyInt(), any(), anyBoolean(), anyBoolean(),
                any(), any(), anyBoolean());
        // Nothing was released: the first hold is the one the buyer still has.
        verify(inventory, never()).releaseReservation(any(), any());
    }

    /**
     * The replay must return the <b>stored</b> total, never a recomputed one.
     * The buyer confirmed a figure on a payment sheet bound to the original
     * intent, and Stripe will charge that figure whatever the tier costs now.
     *
     * <p>Structurally guaranteed rather than merely observed: the key is checked
     * before pricing, so {@code priceIt} is never called a second time.
     */
    @Test
    void replayReturnsTheOriginalAmountAfterAPriceChange() throws Exception {
        var first = create("key-abc");
        assertThat(first.amountMinor()).isEqualTo(5448L);

        // The organizer doubles the tier price between the buyer's two attempts.
        stubPrelude(10000L, 698L);

        var replay = create("key-abc");

        assertThat(replay.amountMinor()).isEqualTo(5448L);
        assertThat(replay.feeMinor()).isEqualTo(448L);
        assertThat(replay.paymentIntentId()).isEqualTo(first.paymentIntentId());
        verify(checkoutService, times(1)).priceIt(any(), any(), anyInt(), any(), any());
    }

    @Test
    void differentKeysAreDifferentPurchases() throws Exception {
        var first = create("key-abc");
        var second = create("key-def");

        assertThat(second.paymentIntentId()).isNotEqualTo(first.paymentIntentId());
        verify(paymentIntents, times(2)).create(any(PaymentIntentCreateParams.class));
    }

    /** The web sends no key and must be byte-identical to before. */
    @Test
    void noKeyIsUnchangedBehaviour() throws Exception {
        create(null);
        create("   ");

        verify(paymentIntents, times(2)).create(any(PaymentIntentCreateParams.class));
        verify(inventory, never()).claimIdempotencyKey(any(), anyString());
        verify(inventory, never()).findByIdempotencyKey(anyString());
    }

    /**
     * Two retries arriving at once. The lookup cannot settle this — both read
     * "no such key" — so the unique index does, and the loser must give its
     * seats back rather than sit on a hold nobody will ever pay for.
     */
    @Test
    void aConcurrentDuplicateReleasesItsHoldAndReplaysTheWinner() throws Exception {
        var winner = create("key-race");

        // The loser: a second in-flight retry whose lookup ran before the
        // winner's row was visible, so it reserved and only then hit the index.
        UUID loserHold = stubPrelude(5000L, 448L);
        blindNextLookup("key-race");

        var answer = create("key-race");

        assertThat(answer).isEqualTo(winner);
        // Seats back in the pool immediately — before Stripe was asked for
        // anything, so no second intent exists either.
        verify(inventory).releaseReservation(loserHold, "IDEMPOTENT_REPLAY");
        verify(paymentIntents, times(1)).create(any(PaymentIntentCreateParams.class));
    }

    /**
     * The hold behind the key is gone — expired, swept, or already paid. Handing
     * back its client secret would bind a payment sheet to seats that are no
     * longer this buyer's, so say so instead.
     */
    @Test
    void aReplayAgainstADeadReservationIs409() throws Exception {
        create("key-abc");
        claimed.get("key-abc").setStatus(ReservationStatus.RELEASED);

        assertThatThrownBy(() -> create("key-abc"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no longer active");
    }

    /**
     * The first attempt died between claiming the key and learning the intent
     * id. Refuse rather than mint a second intent against a hold that may
     * already have one — that is how a buyer gets charged twice.
     */
    @Test
    void aReplayBeforeTheIntentIdLandedIs409() throws Exception {
        create("key-abc");
        claimed.get("key-abc").setStripeSessionId(null);

        assertThatThrownBy(() -> create("key-abc"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already in progress");
    }

    /**
     * Truncating to the column width would silently collapse two distinct keys
     * that share a prefix into one purchase — the exact failure this feature
     * exists to prevent, arrived at from the other direction.
     */
    @Test
    void anOverlongKeyIsRejectedRatherThanTruncated() {
        String tooLong = "k".repeat(129);

        assertThatThrownBy(() -> create(tooLong))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Idempotency-Key");

        verify(inventory, never()).claimIdempotencyKey(any(), anyString());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private StripePaymentIntentService.NativeIntent create(String key) {
        return service.create(EVENT, TIER, 2, null, null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en", key);
    }

    /**
     * Re-stubs the shared prelude with a fresh reservation row and returns its
     * id. Called again mid-test to simulate a price change or a second hold.
     */
    private UUID stubPrelude(long subtotal, long fee) {
        UUID reservationId = UUID.randomUUID();
        TicketReservation row = new TicketReservation();
        row.setId(reservationId);
        row.setTierId(TIER);
        row.setQty(2);
        row.setStatus(ReservationStatus.HELD);
        rows.put(reservationId, row);

        when(checkoutService.priceIt(any(), any(), anyInt(), any(), any()))
                .thenReturn(new StripeCheckoutService.Priced(null, null, null, subtotal, 0L, subtotal));

        var org = new com.imin.iminapi.model.Organization();
        org.setStripeAccountId("acct_test_org");

        var metadata = new HashMap<String, String>();
        metadata.put("reservation_id", reservationId.toString());
        metadata.put("client", "native");

        when(checkoutService.reserveAndBuildMetadata(
                any(), any(), any(), anyInt(), any(), anyBoolean(), anyBoolean(),
                any(), any(), anyBoolean()))
                .thenReturn(new StripeCheckoutService.PaidPrelude(
                        null, null, org, null, reservationId, java.time.Instant.now(),
                        subtotal, 0L, subtotal, fee, "eur", metadata));
        return reservationId;
    }
}
