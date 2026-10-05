package com.imin.iminapi.payout;

import com.google.gson.Gson;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.exception.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.ApiRequest;
import com.stripe.net.ApiResource;
import com.stripe.net.StripeResponseGetter;
import com.stripe.service.PaymentIntentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.time.Duration;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link OrderSettlementStamper} over a real {@link PaymentIntentService} on a faked response getter, H2.
 * The sandbox charge of 2026-10-05: 1149 USD, fee 149 USD, transfer 1025 EUR, fee balance transaction 133 EUR.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class OrderSettlementStamperTest {

    private static final Gson GSON = new Gson();

    /**
     * A PaymentIntent with its latest charge expanded the way the stamper asks for it. A null transfer amount
     * leaves the charge without a transfer; a null charge fee leaves it without an application fee.
     */
    static String paymentIntentJson(String piId, Long transferMinor, String transferCurrency, String chargeTxnCurrency,
                                    Long chargeFeeMinor, String feeCurrency, Long feeTxnMinor, String feeTxnCurrency) {
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("object", "charge");
        ch.put("id", "ch_" + piId);
        ch.put("balance_transaction", Map.of("object", "balance_transaction", "id", "txn_" + piId,
                "amount", transferMinor == null ? 0L : transferMinor, "currency", chargeTxnCurrency,
                "exchange_rate", 0.892011));
        if (transferMinor != null) {
            ch.put("transfer", Map.of("object", "transfer", "id", "tr_" + piId, "amount", transferMinor,
                    "currency", transferCurrency));
        }
        if (chargeFeeMinor != null) {
            ch.put("application_fee_amount", chargeFeeMinor);
            Map<String, Object> fee = new LinkedHashMap<>();
            fee.put("object", "application_fee");
            fee.put("id", "fee_" + piId);
            fee.put("amount", chargeFeeMinor);
            fee.put("currency", feeCurrency);
            if (feeTxnMinor != null) {
                fee.put("balance_transaction", Map.of("object", "balance_transaction", "id", "txn_fee_" + piId,
                        "amount", feeTxnMinor, "currency", feeTxnCurrency, "type", "application_fee"));
            }
            ch.put("application_fee", fee);
        }
        return GSON.toJson(Map.of("object", "payment_intent", "id", piId, "latest_charge", ch));
    }

    /** The sandbox USD order as Stripe reports it. */
    static String usdProbe(String piId) {
        return paymentIntentJson(piId, 1_025L, "eur", "eur", 149L, "usd", 133L, "eur");
    }

    /** Stripe as the stamper sees it: one canned answer per PaymentIntent id. */
    static class FakeStripe {
        final Map<String, String> paymentIntents = new HashMap<>();
        final List<String> failing = new ArrayList<>();
        /** Ids whose read dies with a RUNTIME error (an SDK or parsing bug), not a StripeException. */
        final List<String> crashing = new ArrayList<>();
        final List<ApiRequest> reads = new ArrayList<>();

        Object handle(ApiRequest req, Type type) throws Exception {
            String path = req.getPath();
            if (path.startsWith("/v1/payment_intents/")) {
                reads.add(req);
                String id = path.substring("/v1/payment_intents/".length());
                if (failing.contains(id)) throw new ApiException("An unknown error occurred", null, null, 500, null);
                if (crashing.contains(id)) throw new IllegalStateException("simulated parse failure");
                String json = paymentIntents.get(id);
                if (json == null) throw new IllegalStateException("no canned payment intent " + id);
                return ApiResource.GSON.fromJson(json, PaymentIntent.class);
            }
            throw new IllegalStateException("unexpected Stripe call in test: " + path);
        }
    }

    @Autowired OrderSettlementStamper stamper;
    @Autowired StripeProperties props;
    @MockitoSpyBean OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    @MockitoBean StripeClient stripeClient;

    private final FakeStripe fake = new FakeStripe();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Organization org;
    private Event event;
    private boolean runningMode;

    @BeforeEach
    void setUp() throws Exception {
        wipe();
        StripeResponseGetter rg = mock(StripeResponseGetter.class);
        when(rg.request(any(ApiRequest.class), any(Type.class)))
                .thenAnswer(inv -> fake.handle(inv.getArgument(0), inv.getArgument(1)));
        when(stripeClient.paymentIntents()).thenReturn(new PaymentIntentService(rg));
        runningMode = !props.isLiveKey();
        logs.start();
        ((Logger) LoggerFactory.getLogger(OrderSettlementStamper.class)).addAppender(logs);
        org = newOrg();
        event = newEvent();
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(OrderSettlementStamper.class)).detachAppender(logs);
        wipe();
    }

    private void wipe() {
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void stamps_transfer_and_fee_from_one_expanded_retrieve() {
        Order o = unstamped(1_149, 149, runningMode);
        fake.paymentIntents.put(o.getStripePaymentIntentId(), usdProbe(o.getStripePaymentIntentId()));

        stamper.stampOrg(org);

        assertThat(fake.reads).hasSize(1);
        ApiRequest r = fake.reads.get(0);
        assertThat(r.getMethod()).isEqualTo(ApiResource.RequestMethod.GET);
        assertThat(r.getPath()).isEqualTo("/v1/payment_intents/" + o.getStripePaymentIntentId());
        assertThat(r.getParams()).containsOnlyKeys("expand");
        assertThat((List<Object>) r.getParams().get("expand")).containsExactly(
                "latest_charge.balance_transaction", "latest_charge.transfer", "latest_charge.application_fee",
                "latest_charge.application_fee.balance_transaction");
        Order s = reload(o);
        assertThat(s.getSettlementCurrency()).isEqualTo("eur");
        assertThat(s.getSettlementGrossMinor()).isEqualTo(1_025L);
        assertThat(s.getSettlementFeeMinor()).as("the fee balance transaction, not the 149 USD fee object")
                .isEqualTo(133L);
    }

    @Test
    void a_stamped_order_is_never_read_again() {
        Order stamped = unstamped(1_149, 149, runningMode);
        orders.stampSettlement(stamped.getId(), "eur", 1_149, 149);
        Order fresh = unstamped(2_298, 298, runningMode);
        fake.paymentIntents.put(stamped.getStripePaymentIntentId(), usdProbe(stamped.getStripePaymentIntentId()));
        fake.paymentIntents.put(fresh.getStripePaymentIntentId(), usdProbe(fresh.getStripePaymentIntentId()));

        stamper.stampOrg(org);

        assertThat(fake.reads).extracting(ApiRequest::getPath)
                .containsExactly("/v1/payment_intents/" + fresh.getStripePaymentIntentId());
        assertThat(reload(stamped).getSettlementGrossMinor()).isEqualTo(1_149L);
    }

    @Test
    void an_order_of_the_other_key_mode_is_not_read() {
        Order other = unstamped(1_149, 149, !runningMode);
        fake.paymentIntents.put(other.getStripePaymentIntentId(), usdProbe(other.getStripePaymentIntentId()));

        stamper.stampOrg(org);

        assertThat(fake.reads).isEmpty();
        assertThat(reload(other).getSettlementCurrency()).isNull();
    }

    @Test
    void a_free_order_is_not_read() {
        Order free = unstamped(0, 0, runningMode);
        fake.paymentIntents.put(free.getStripePaymentIntentId(), usdProbe(free.getStripePaymentIntentId()));

        stamper.stampOrg(org);

        assertThat(fake.reads).isEmpty();
    }

    @Test
    void a_charge_without_a_transfer_is_left_unstamped() {
        Order o = unstamped(1_149, 149, runningMode);
        fake.paymentIntents.put(o.getStripePaymentIntentId(), paymentIntentJson(o.getStripePaymentIntentId(),
                null, null, "eur", 149L, "usd", 133L, "eur"));

        stamper.stampOrg(org);

        assertThat(fake.reads).hasSize(1);
        assertUnstamped(o);
    }

    @Test
    void a_fee_in_another_currency_is_left_unstamped() {
        Order o = unstamped(1_149, 149, runningMode);
        fake.paymentIntents.put(o.getStripePaymentIntentId(), paymentIntentJson(o.getStripePaymentIntentId(),
                1_025L, "eur", "eur", 149L, "usd", 149L, "usd"));

        stamper.stampOrg(org);

        assertThat(fake.reads).hasSize(1);
        assertUnstamped(o);
    }

    @Test
    void a_fee_without_its_balance_transaction_is_left_unstamped() {
        Order o = unstamped(1_149, 149, runningMode);
        fake.paymentIntents.put(o.getStripePaymentIntentId(), paymentIntentJson(o.getStripePaymentIntentId(),
                1_025L, "eur", "eur", 149L, "usd", null, null));

        stamper.stampOrg(org);

        assertThat(fake.reads).hasSize(1);
        assertUnstamped(o);
    }

    @Test
    void a_charge_without_a_fee_stamps_zero() {
        Order o = unstamped(1_000, 0, runningMode);
        fake.paymentIntents.put(o.getStripePaymentIntentId(), paymentIntentJson(o.getStripePaymentIntentId(),
                892L, "eur", "eur", null, null, null, null));

        stamper.stampOrg(org);

        Order s = reload(o);
        assertThat(s.getSettlementCurrency()).isEqualTo("eur");
        assertThat(s.getSettlementGrossMinor()).isEqualTo(892L);
        assertThat(s.getSettlementFeeMinor()).isZero();
    }

    @Test
    void a_fee_above_the_transfer_is_left_unstamped() {
        Order o = unstamped(1_149, 149, runningMode);
        fake.paymentIntents.put(o.getStripePaymentIntentId(), paymentIntentJson(o.getStripePaymentIntentId(),
                100L, "eur", "eur", 149L, "usd", 133L, "eur"));

        stamper.stampOrg(org);

        assertThat(fake.reads).hasSize(1);
        assertUnstamped(o);
    }

    @Test
    void a_failed_read_leaves_that_order_and_stamps_the_next() {
        Order first = unstamped(1_149, 149, runningMode);
        Order second = unstamped(1_149, 149, runningMode);
        fake.failing.add(first.getStripePaymentIntentId());
        fake.paymentIntents.put(second.getStripePaymentIntentId(), usdProbe(second.getStripePaymentIntentId()));

        stamper.stampOrg(org);

        assertThat(fake.reads).extracting(ApiRequest::getPath).containsExactlyInAnyOrder(
                "/v1/payment_intents/" + first.getStripePaymentIntentId(),
                "/v1/payment_intents/" + second.getStripePaymentIntentId());
        assertUnstamped(first);
        assertThat(reload(second).getSettlementGrossMinor()).isEqualTo(1_025L);
    }

    @Test
    void newer_orders_are_read_first() {
        Order older = unstamped(1_149, 149, runningMode);
        Order newer = unstamped(1_149, 149, runningMode);
        fake.paymentIntents.put(older.getStripePaymentIntentId(), usdProbe(older.getStripePaymentIntentId()));
        fake.paymentIntents.put(newer.getStripePaymentIntentId(), usdProbe(newer.getStripePaymentIntentId()));

        stamper.stampOrg(org);

        assertThat(fake.reads).extracting(ApiRequest::getPath).containsExactly(
                "/v1/payment_intents/" + newer.getStripePaymentIntentId(),
                "/v1/payment_intents/" + older.getStripePaymentIntentId());
    }

    @Test
    void a_runtime_failure_on_one_order_still_stamps_the_next() {
        Order older = unstamped(1_149, 149, runningMode);
        Order newer = unstamped(1_149, 149, runningMode);
        fake.crashing.add(newer.getStripePaymentIntentId());   // read first
        fake.paymentIntents.put(older.getStripePaymentIntentId(), usdProbe(older.getStripePaymentIntentId()));

        assertThatCode(() -> stamper.stampOrg(org)).doesNotThrowAnyException();

        assertUnstamped(newer);
        assertThat(reload(older).getSettlementGrossMinor()).isEqualTo(1_025L);
        assertThat(errorsMentioning(newer.getId().toString())).hasSize(1);
    }

    @Test
    void a_failed_listing_never_throws() {
        doThrow(new IllegalStateException("simulated database failure"))
                .when(orders).findUnstampedPaidByOrgId(any(), anyBoolean(), any());

        assertThatCode(() -> stamper.stampOrg(org)).doesNotThrowAnyException();

        assertThat(fake.reads).isEmpty();
        assertThat(errorsMentioning(org.getId().toString())).hasSize(1);
    }

    @Test
    void a_failed_write_leaves_that_order_and_stamps_the_next() {
        Order older = unstamped(1_149, 149, runningMode);
        Order newer = unstamped(1_149, 149, runningMode);
        fake.paymentIntents.put(older.getStripePaymentIntentId(), usdProbe(older.getStripePaymentIntentId()));
        fake.paymentIntents.put(newer.getStripePaymentIntentId(), usdProbe(newer.getStripePaymentIntentId()));
        doThrow(new IllegalStateException("simulated write failure"))
                .when(orders).stampSettlement(eq(newer.getId()), any(), anyLong(), anyLong());

        assertThatCode(() -> stamper.stampOrg(org)).doesNotThrowAnyException();

        assertUnstamped(newer);
        assertThat(reload(older).getSettlementGrossMinor()).isEqualTo(1_025L);
        assertThat(errorsMentioning(newer.getId().toString())).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage).asString().contains("could not write the stamp");
    }

    @Test
    void a_failed_read_is_an_error_only_once_the_order_is_older_than_the_payout_buffer() {
        Duration buffer = Duration.ofDays(props.getPayoutBufferDays());
        Order stuck = unstamped(1_149, 149, runningMode, Instant.now().minus(buffer).minus(Duration.ofHours(1)));
        Order fresh = unstamped(1_149, 149, runningMode, Instant.now().minus(buffer).plus(Duration.ofHours(1)));
        fake.failing.add(stuck.getStripePaymentIntentId());
        fake.failing.add(fresh.getStripePaymentIntentId());

        stamper.stampOrg(org);

        assertThat(levelsMentioning(stuck.getId().toString())).containsExactly(Level.ERROR);
        assertThat(levelsMentioning(fresh.getId().toString())).containsExactly(Level.WARN);
    }

    @Test
    void a_balance_transaction_in_another_currency_is_left_unstamped() {
        Order o = unstamped(1_149, 149, runningMode);
        fake.paymentIntents.put(o.getStripePaymentIntentId(), paymentIntentJson(o.getStripePaymentIntentId(),
                1_025L, "eur", "usd", 149L, "usd", 133L, "eur"));

        stamper.stampOrg(org);

        assertThat(fake.reads).hasSize(1);
        assertUnstamped(o);
    }

    @Test
    void a_stamp_is_written_once_and_survives_a_full_save() {
        Order o = unstamped(1_149, 149, runningMode);
        Order staleBeforeStamp = orders.findById(o.getId()).orElseThrow();

        assertThat(orders.stampSettlement(o.getId(), "eur", 1_025, 133)).isEqualTo(1);
        assertThat(orders.stampSettlement(o.getId(), "gbp", 900, 100)).as("only an unstamped row takes a stamp")
                .isZero();

        // SmsConsentService's path: a full-entity save of a snapshot read before the stamp.
        staleBeforeStamp.setSmsMarketingOptIn(true);
        orders.save(staleBeforeStamp);

        Order s = reload(o);
        assertThat(s.isSmsMarketingOptIn()).isTrue();
        assertThat(s.getSettlementCurrency()).isEqualTo("eur");
        assertThat(s.getSettlementGrossMinor()).isEqualTo(1_025L);
        assertThat(s.getSettlementFeeMinor()).isEqualTo(133L);
    }

    @Test
    void a_full_save_before_the_stamp_does_not_block_it() {
        Order o = unstamped(1_149, 149, runningMode);
        Order loaded = orders.findById(o.getId()).orElseThrow();
        loaded.setSmsMarketingOptIn(true);
        orders.save(loaded);

        assertThat(orders.stampSettlement(o.getId(), "eur", 1_025, 133)).isEqualTo(1);

        Order s = reload(o);
        assertThat(s.isSmsMarketingOptIn()).isTrue();
        assertThat(s.getSettlementGrossMinor()).isEqualTo(1_025L);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private void assertUnstamped(Order o) {
        Order s = reload(o);
        assertThat(s.getSettlementCurrency()).isNull();
        assertThat(s.getSettlementGrossMinor()).isNull();
        assertThat(s.getSettlementFeeMinor()).isNull();
    }

    private Order reload(Order o) {
        return orders.findById(o.getId()).orElseThrow();
    }

    private List<Level> levelsMentioning(String id) {
        return logs.list.stream().filter(e -> e.getFormattedMessage().contains(id)).map(ILoggingEvent::getLevel).toList();
    }

    private List<ILoggingEvent> errorsMentioning(String id) {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains(id))
                .toList();
    }

    private Order unstamped(long total, long fee, boolean testMode) {
        // Distinct created_at so the stamper's newest-first order is the reverse creation order.
        return unstamped(total, fee, testMode, Instant.now().minusSeconds(1_000).plusMillis(orders.count()));
    }

    private Order unstamped(long total, long fee, boolean testMode, Instant createdAt) {
        Order o = new Order();
        o.setToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(total);
        o.setCurrency("usd");
        o.setApplicationFeeMinor(fee);
        o.setPaymentMethod("card");
        o.setTestMode(testMode);
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        o.setCreatedAt(createdAt);
        return orders.save(o);
    }

    private Organization newOrg() {
        Organization o = new Organization();
        o.setName("Stamp Org");
        o.setSlug("stamp-org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("o@test.example");
        o.setCountry("FR");
        o.setStripeAccountId("acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        o.setStripeConnectState(StripeConnectState.ACTIVE);
        return orgs.save(o);
    }

    private Event newEvent() {
        User u = new User();
        u.setOrgId(org.getId());
        u.setEmail("c-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        UUID userId = users.save(u).getId();
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Ended");
        e.setSlug("ended-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setCurrency("USD");
        e.setEndsAt(Instant.now().minus(10, ChronoUnit.DAYS));
        e.setCreatedBy(userId);
        return events.save(e);
    }
}
