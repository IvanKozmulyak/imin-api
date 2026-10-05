package com.imin.iminapi.payout;

import com.google.gson.Gson;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.stripe.StripeConnectState;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.net.ApiRequest;
import com.stripe.net.ApiResource;
import com.stripe.net.StripeResponseGetter;
import com.stripe.service.AccountService;
import com.stripe.service.BalanceService;
import com.stripe.service.ChargeService;
import com.stripe.service.PayoutService;
import com.stripe.service.TransferService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LOST-dispute recovery, the return on a later win and the sibling-payout hold, in an own live-key context
 * (no shared bean mutated), Stripe faked by method and path. Orders: €11.49 = €10.00 + 149 fee, or 2298 / 298.
 */
@SpringBootTest(properties = {
        "imin.stripe.secret-key=sk_live_dummy_for_tests",
        "imin.stripe.payout-schedule-manual=true"})
@Import(TestRateLimitConfig.class)
class PostEventPayoutDisputeRecoveryTest {

    private static final Gson GSON = new Gson();

    /** One recorded money-moving request. */
    record Call(String path, long amount, String key, Map<String, Object> params) {}

    /** Stripe as the recovery sees it: charges, transfers with reversals, the platform transfers, payouts. */
    static class FakeStripe {
        final Map<String, String> chargeTransfer = new HashMap<>();
        final Map<String, long[]> transfers = new HashMap<>();            // id -> {amount, reversed}
        final Map<String, List<Map<String, Object>>> reversals = new HashMap<>();
        final Map<String, String> transferCurrency = new HashMap<>();   // default eur
        /** The platform's transfers to connected accounts, served in pages of {@code limit}. */
        final List<Map<String, Object>> platformTransfers = new ArrayList<>();
        final List<Map<String, Object>> transferListParams = new ArrayList<>();
        StripeResponseGetter rg;
        final List<Call> reversalCreates = new ArrayList<>();
        final List<Call> transferCreates = new ArrayList<>();
        final List<Call> payouts = new ArrayList<>();
        final List<String> recoveryReads = new ArrayList<>();
        /** Canned PaymentIntents the settlement stamper reads, by id; and the ids it read. */
        final Map<String, String> paymentIntents = new HashMap<>();
        final List<String> paymentIntentReads = new ArrayList<>();
        long available = 50_000L;
        String reversalFailure;      // "balance_insufficient" | "server" | "timeout_after" (executed, then timed out) | null
        String transferFailure;      // "balance_insufficient" | "server" | "timeout_after" | null
        boolean crashOnAccount;

        Object handle(ApiRequest req, Type type) throws Exception {
            String path = req.getPath();
            boolean post = req.getMethod() == ApiResource.RequestMethod.POST;
            String key = req.getOptions() == null ? null : req.getOptions().getIdempotencyKey();
            Map<String, Object> params = req.getParams() == null ? Map.of() : req.getParams();
            if (path.startsWith("/v1/payment_intents/")) {
                String id = path.substring("/v1/payment_intents/".length());
                paymentIntentReads.add(id);
                return ApiResource.GSON.fromJson(paymentIntents.get(id), type);
            }
            if (path.startsWith("/v1/balance")) {
                return json(Map.of("object", "balance",
                        "available", List.of(Map.of("amount", available, "currency", "eur")),
                        "pending", List.of()), type);
            }
            if (path.startsWith("/v1/accounts")) {
                if (crashOnAccount) throw new IllegalStateException("simulated crash after recovery");
                return json(Map.of("object", "account", "id", "acct_x", "external_accounts",
                        Map.of("object", "list", "data", List.of(Map.of("object", "bank_account", "id", "ba_1")))), type);
            }
            if (path.startsWith("/v1/payouts")) {
                long amount = ((Number) params.get("amount")).longValue();
                payouts.add(new Call(path, amount, key, params));
                return json(Map.of("object", "payout", "id", "po_" + payouts.size(), "amount", amount,
                        "currency", "eur", "status", "pending"), type);
            }
            if (path.startsWith("/v1/charges/")) {
                String id = path.substring("/v1/charges/".length());
                recoveryReads.add(path);
                Map<String, Object> ch = new LinkedHashMap<>();
                ch.put("object", "charge");
                ch.put("id", id);
                ch.put("transfer", chargeTransfer.get(id));
                return json(ch, type);
            }
            if (path.matches("/v1/transfers/[^/]+/reversals")) {
                String tr = path.split("/")[3];
                if (!post) {
                    recoveryReads.add(path);
                    return page(reversals.getOrDefault(tr, List.of()), path, params, req, type);
                }
                long amount = ((Number) params.get("amount")).longValue();
                reversalCreates.add(new Call(path, amount, key, params));
                if ("balance_insufficient".equals(reversalFailure)) {
                    throw new InvalidRequestException("Insufficient funds", "amount", null,
                            "balance_insufficient", 400, null);
                }
                if ("server".equals(reversalFailure)) {
                    throw new ApiException("An unknown error occurred", null, null, 500, null);
                }
                Map<String, Object> rv = new LinkedHashMap<>();
                rv.put("object", "transfer_reversal");
                rv.put("id", "trr_" + reversalCreates.size());
                rv.put("amount", amount);
                rv.put("metadata", params.getOrDefault("metadata", Map.of()));
                reversals.computeIfAbsent(tr, k -> new ArrayList<>()).add(rv);
                long[] t = transfers.get(tr);
                if (t != null) t[1] += amount;
                if ("timeout_after".equals(reversalFailure)) {
                    reversalFailure = null;   // Stripe did it; only the answer was lost
                    throw new ApiConnectionException("IOException during API request: read timed out");
                }
                return json(rv, type);
            }
            if (path.startsWith("/v1/transfers/")) {
                String tr = path.substring("/v1/transfers/".length());
                recoveryReads.add(path);
                long[] t = transfers.getOrDefault(tr, new long[] {0L, 0L});
                return json(Map.of("object", "transfer", "id", tr, "amount", t[0], "amount_reversed", t[1],
                        "currency", transferCurrency.getOrDefault(tr, "eur")), type);
            }
            if (path.equals("/v1/transfers")) {
                if (!post) {
                    recoveryReads.add(path);
                    transferListParams.add(params);
                    return page(platformTransfers, path, params, req, type);
                }
                long amount = ((Number) params.get("amount")).longValue();
                transferCreates.add(new Call(path, amount, key, params));
                if ("balance_insufficient".equals(transferFailure)) {
                    throw new InvalidRequestException("Insufficient funds", "amount", null,
                            "balance_insufficient", 400, null);
                }
                if ("server".equals(transferFailure)) {
                    throw new ApiException("An unknown error occurred", null, null, 500, null);
                }
                Map<String, Object> tr = new LinkedHashMap<>();
                tr.put("object", "transfer");
                tr.put("id", "tr_back_" + transferCreates.size());
                tr.put("amount", amount);
                tr.put("metadata", params.getOrDefault("metadata", Map.of()));
                platformTransfers.add(tr);
                if ("timeout_after".equals(transferFailure)) {
                    transferFailure = null;   // Stripe did it; only the answer was lost
                    throw new ApiConnectionException("IOException during API request: read timed out");
                }
                return json(tr, type);
            }
            throw new IllegalStateException("unexpected Stripe call in test: " + req.getMethod() + " " + path);
        }

        /** One page of {@code all} after {@code starting_after}, wired for auto-paging like the live getter. */
        private Object page(List<Map<String, Object>> all, String path, Map<String, Object> params,
                            ApiRequest req, Type type) {
            int limit = params.get("limit") == null ? 10 : ((Number) params.get("limit")).intValue();
            int from = 0;
            Object after = params.get("starting_after");
            for (int i = 0; after != null && i < all.size(); i++) {
                if (after.equals(all.get(i).get("id"))) from = i + 1;
            }
            int to = Math.min(all.size(), from + limit);
            Object list = json(Map.of("object", "list", "url", path, "has_more", to < all.size(),
                    "data", all.subList(from, to)), type);
            com.stripe.model.StripeCollection<?> c = (com.stripe.model.StripeCollection<?>) list;
            c.setResponseGetter(rg);
            c.setRequestParams(params);
            c.setRequestOptions(req.getOptions());
            return list;
        }

        private static Object json(Object body, Type type) {
            return ApiResource.GSON.fromJson(GSON.toJson(body), type);
        }
    }

    @Autowired PostEventPayoutService service;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @MockitoSpyBean OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired DisputeRepository disputes;
    @Autowired PayoutRunRepository payoutRuns;
    @Autowired com.imin.iminapi.dispute.DisputeWithholding withholding;
    @Autowired UserRepository users;

    @MockitoBean StripeClient stripeClient;
    @MockitoSpyBean DisputeRecoveryMarker marker;

    private final FakeStripe fake = new FakeStripe();
    private Organization org;
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        wipe();
        StripeResponseGetter rg = mock(StripeResponseGetter.class);
        fake.rg = rg;
        when(rg.request(any(ApiRequest.class), any(Type.class)))
                .thenAnswer(inv -> fake.handle(inv.getArgument(0), inv.getArgument(1)));
        when(stripeClient.balance()).thenReturn(new BalanceService(rg));
        when(stripeClient.payouts()).thenReturn(new PayoutService(rg));
        when(stripeClient.accounts()).thenReturn(new AccountService(rg));
        when(stripeClient.charges()).thenReturn(new ChargeService(rg));
        when(stripeClient.transfers()).thenReturn(new TransferService(rg));
        when(stripeClient.paymentIntents()).thenReturn(new com.stripe.service.PaymentIntentService(rg));
        org = org("acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private void wipe() {
        disputes.deleteAll();
        refunds.deleteAll();
        payoutRuns.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    // ── recovery ────────────────────────────────────────────────────────────────

    @Test
    void nothing_owed_makes_no_recovery_call() {
        Event e = event();
        order(e, 3_300, 300);

        service.payOneEvent(e.getId());

        assertThat(fake.recoveryReads).isEmpty();
        assertThat(fake.reversalCreates).isEmpty();
        assertThat(fake.transferCreates).isEmpty();
        assertThat(fake.payouts).extracting(Call::amount).containsExactly(3_000L);
    }

    @Test
    void a_dispute_in_another_currency_is_never_reversed() {
        Order o = order(event(), 1_149, 149);
        Dispute d = lost(o, 1_149, 1_149);
        d.setCurrency("usd");
        disputes.save(d);

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).isEmpty();
        assertThat(reload(d).getRecoveredAt()).as("the debt stays open").isNull();
    }

    @Test
    void an_order_refunded_before_the_loss_owes_nothing() {
        Order o = order(event(), 1_149, 149);
        refund(o, 1_149, 149, false);
        Dispute d = lost(o, 1_149, 1_149);

        service.recoverForOrg(org.getId());

        assertThat(fake.recoveryReads).isEmpty();
        assertThat(fake.reversalCreates).isEmpty();
        Dispute r = reload(d);
        assertThat(r.getRecoveredAt()).isNotNull();
        assertThat(r.getRecoveredMinor()).isZero();
        assertThat(r.getRecoveryReversalId()).isNull();
    }

    @Test
    void a_dispute_without_a_charge_id_stays_open() {
        Order o = order(event(), 1_149, 149);
        Dispute d = lost(o, 1_149, 1_149);
        d.setStripeChargeId(null);
        disputes.save(d);

        service.recoverForOrg(org.getId());

        assertThat(fake.recoveryReads).isEmpty();
        assertThat(reload(d).getRecoveredAt()).isNull();
    }

    @Test
    void a_charge_without_a_transfer_stays_open() {
        Order o = order(event(), 1_149, 149);
        Dispute d = lost(o, 1_149, 1_149);
        fake.chargeTransfer.put(d.getStripeChargeId(), null);

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).isEmpty();
        assertThat(reload(d).getRecoveredAt()).isNull();
    }

    @Test
    void an_existing_reversal_is_adopted_not_created_again() {
        Order o = order(event(), 1_149, 149);
        Dispute d = lost(o, 1_149, 1_149);
        String tr = fake.chargeTransfer.get(d.getStripeChargeId());
        fake.reversals.put(tr, new ArrayList<>(List.of(Map.of("object", "transfer_reversal", "id", "trr_old",
                "amount", 1_000, "metadata", Map.of("dispute_id", d.getId().toString())))));

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).isEmpty();
        Dispute r = reload(d);
        assertThat(r.getRecoveryReversalId()).isEqualTo("trr_old");
        assertThat(r.getRecoveredMinor()).isEqualTo(1_000L);
    }

    @Test
    void a_fully_reversed_transfer_leaves_the_debt_open() {
        Order o = order(event(), 1_149, 149);
        Dispute d = lost(o, 1_149, 1_149);
        fake.transfers.get(fake.chargeTransfer.get(d.getStripeChargeId()))[1] = 1_149L;

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).isEmpty();
        assertThat(reload(d).getRecoveredAt()).as("the 1000 owed stays open").isNull();
        assertThat(withholding.owedOnOrder(o)).isEqualTo(1_000L);
    }

    @Test
    void the_reversal_is_capped_at_what_the_transfer_has_left() {
        Order o = order(event(), 1_149, 149);
        Dispute d = lost(o, 1_149, 1_149);
        fake.transfers.get(fake.chargeTransfer.get(d.getStripeChargeId()))[1] = 500L;

        service.recoverForOrg(org.getId());

        // owed 1000, transfer 1149 − 500 reversed = 649 left
        assertThat(fake.reversalCreates).extracting(Call::amount).containsExactly(649L);
        assertThat(reload(d).getRecoveredMinor()).isEqualTo(649L);
    }

    @Test
    void worked_A_a_lost_11_49_order_reverses_the_10_00_ticket_share() {
        Order o = order(event(), 1_149, 149);
        Dispute d = lost(o, 1_149, 1_149);
        String tr = fake.chargeTransfer.get(d.getStripeChargeId());

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).hasSize(1);
        Call c = fake.reversalCreates.get(0);
        assertThat(c.path()).isEqualTo("/v1/transfers/" + tr + "/reversals");
        assertThat(c.amount()).as("1149 less its 149 booking fee").isEqualTo(1_000L);
        assertThat(c.key()).isEqualTo("dispute:" + d.getId() + ":reversal:1000");
        assertThat(c.params().get("metadata")).isEqualTo(Map.of("dispute_id", d.getId().toString(),
                "stripe_dispute_id", d.getStripeDisputeId(), "dispute_reversed_before", "0"));
        assertThat(c.params()).doesNotContainKey("refund_application_fee");
        Dispute r = reload(d);
        assertThat(r.getRecoveredAt()).isNotNull();
        assertThat(r.getRecoveredMinor()).isEqualTo(1_000L);
        assertThat(r.getRecoveryReversalId()).isEqualTo("trr_1");
    }

    @Test
    void worked_D_a_full_charge_dispute_after_a_refund_reverses_only_the_remaining_stake() {
        Order o = order(event(), 2_298, 298);
        refund(o, 1_149, 149, false);
        Dispute d = lost(o, 2_298, 2_298);
        fake.transfers.get(fake.chargeTransfer.get(d.getStripeChargeId()))[1] = 1_149L;

        service.recoverForOrg(org.getId());

        // gross min(2298, 2298 − 1149) = 1149; fee 149; share 1000
        assertThat(fake.reversalCreates).extracting(Call::amount).containsExactly(1_000L);
    }

    @Test
    void a_platform_funded_refund_is_reversed_before_the_dispute_on_the_same_transfer() {
        Order o = order(event(), 2_298, 298);
        Refund fronted = refund(o, 1_149, 149, true);
        Dispute d = lost(o, 2_298, 2_298);
        String tr = fake.chargeTransfer.get(d.getStripeChargeId());

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).extracting(Call::key).containsExactly(
                "refund:" + fronted.getId() + ":reversal", "dispute:" + d.getId() + ":reversal:1000");
        assertThat(fake.reversalCreates).extracting(Call::amount).containsExactly(1_000L, 1_000L);
        assertThat(fake.transfers.get(tr)[1]).as("2000 of the 2298 transfer reversed").isEqualTo(2_000L);
    }

    @Test
    void a_refused_reversal_leaves_the_debt_open_and_sibling_payouts_hold_it_back() {
        Event x = event();
        Dispute d = lost(order(x, 1_149, 149), 1_149, 1_149);
        Event y = event();
        order(y, 3_300, 300);                     // net 3000
        fake.reversalFailure = "balance_insufficient";
        fake.available = 3_500L;

        service.payOneEvent(y.getId());

        assertThat(reload(d).getRecoveredAt()).isNull();
        assertThat(fake.payouts).extracting(Call::amount)
                .as("min(3000, 3500 − 1000 held back)").containsExactly(2_500L);
    }

    @Test
    void another_stripe_error_leaves_the_debt_open_and_does_not_escape() {
        Event x = event();
        Dispute d = lost(order(x, 1_149, 149), 1_149, 1_149);
        Event y = event();
        order(y, 3_300, 300);
        fake.reversalFailure = "server";

        assertThatCode(() -> service.payOneEvent(y.getId())).doesNotThrowAnyException();

        assertThat(reload(d).getRecoveredAt()).isNull();
        assertThat(fake.payouts).hasSize(1);
    }

    @Test
    void a_failed_marker_is_healed_by_the_lookup_on_the_next_tick() {
        Dispute d = lost(order(event(), 1_149, 149), 1_149, 1_149);
        doThrow(new IllegalStateException("db down")).doCallRealMethod()
                .when(marker).markRecovered(any(), anyLong(), anyLong(), any(), anyBoolean());

        assertThatCode(() -> service.recoverForOrg(org.getId())).doesNotThrowAnyException();
        assertThat(reload(d).getRecoveredAt()).isNull();

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).as("the second tick adopts, it does not create").hasSize(1);
        assertThat(reload(d).getRecoveryReversalId()).isEqualTo("trr_1");
        assertThat(reload(d).getRecoveredMinor()).isEqualTo(1_000L);
    }

    @Test
    void a_test_mode_dispute_is_not_recovered_under_a_live_key() {
        Dispute d = lost(order(event(), 1_149, 149), 1_149, 1_149);
        d.setTestMode(true);
        disputes.save(d);

        service.recoverForOrg(org.getId());

        assertThat(fake.recoveryReads).isEmpty();
        assertThat(reload(d).getRecoveredAt()).isNull();
    }

    @Test
    void only_lost_disputes_with_an_order_are_recovered() {
        Event e = event();
        Instant earlier = Instant.now().minus(1, ChronoUnit.HOURS);
        Dispute open = dispute(order(e, 1_149, 149), 1_149, DisputeStatus.OPEN, earlier);
        Dispute won = dispute(order(e, 1_149, 149), 1_149, DisputeStatus.WON, earlier);
        Dispute orphan = dispute(null, 1_149, DisputeStatus.LOST, earlier);
        Dispute target = lost(order(e, 1_149, 149), 1_149, 1_149);

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).extracting(Call::key)
                .containsExactly("dispute:" + target.getId() + ":reversal:1000");
        assertThat(List.of(open, won, orphan)).allSatisfy(x -> assertThat(reload(x).getRecoveredAt()).isNull());
    }

    @Test
    void a_recovered_dispute_is_never_reversed_twice() {
        lost(order(event(), 1_149, 149), 1_149, 1_149);

        service.recoverForOrg(org.getId());
        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).hasSize(1);
    }

    @Test
    void the_marker_survives_a_rollback_of_the_payout_transaction() {
        Dispute d = lost(order(event(), 1_149, 149), 1_149, 1_149);
        Event y = event();
        order(y, 3_300, 300);
        fake.crashOnAccount = true;

        assertThatThrownBy(() -> service.payOneEvent(y.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(reload(d).getRecoveredAt()).as("committed in its own transaction").isNotNull();

        fake.crashOnAccount = false;
        service.payOneEvent(y.getId());

        assertThat(fake.reversalCreates).hasSize(1);
    }

    @Test
    void a_second_lost_dispute_on_the_order_reverses_only_what_the_first_did_not() {
        Order o = order(event(), 2_298, 298);
        Dispute first = lost(o, 1_149, 2_298);
        service.recoverForOrg(org.getId());
        Dispute second = dispute(o, 1_149, DisputeStatus.LOST, Instant.now());

        service.recoverForOrg(org.getId());

        // share of 2298 LOST on 2298/298 = 2000, less the 1000 the first reversal still holds
        assertThat(fake.reversalCreates).extracting(Call::key).containsExactly(
                "dispute:" + first.getId() + ":reversal:1000", "dispute:" + second.getId() + ":reversal:1000");
        assertThat(fake.reversalCreates).extracting(Call::amount).containsExactly(1_000L, 1_000L);
    }

    // ── converted orders: sandbox 1149 USD (fee 149) settled as 1025 EUR, fee 133 EUR ──

    @Test
    void worked_W3_a_lost_usd_order_reverses_892_eur() {
        Order o = usdOrder(event());
        Dispute d = lostUsd(o, 1_149, 1_025);
        String tr = fake.chargeTransfer.get(d.getStripeChargeId());

        service.recoverForOrg(org.getId());

        // gross_s(1149) = 1025, fee_s(149) = 133, share min(892, stake 892)
        assertThat(fake.reversalCreates).hasSize(1);
        Call c = fake.reversalCreates.get(0);
        assertThat(c.path()).isEqualTo("/v1/transfers/" + tr + "/reversals");
        assertThat(c.amount()).isEqualTo(892L);
        assertThat(c.key()).isEqualTo("dispute:" + d.getId() + ":reversal:892");
        Dispute r = reload(d);
        assertThat(r.getRecoveredMinor()).isEqualTo(892L);
        assertThat(r.getRecoveredAt()).isNotNull();
    }

    @Test
    void worked_W4_a_won_usd_dispute_returns_892_in_eur() {
        Order o = usdOrder(event());
        Dispute d = lostUsd(o, 1_149, 1_025);
        service.recoverForOrg(org.getId());
        Dispute won = reload(d);
        won.setStatus(DisputeStatus.WON);
        disputes.save(won);

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).hasSize(1);
        Call c = fake.transferCreates.get(0);
        assertThat(c.amount()).isEqualTo(892L);
        assertThat(c.params()).containsEntry("currency", "eur").containsEntry("destination", org.getStripeAccountId());
        assertThat(c.key()).isEqualTo("dispute:" + d.getId() + ":return:0:892");
        assertThat(reload(d).getReturnedMinor()).isEqualTo(892L);
    }

    @Test
    void worked_W2_a_platform_funded_usd_refund_reverses_446_eur() {
        Order o = usdOrder(event());
        Refund fronted = refund(o, 574, 74, true);
        fronted.setCurrency("usd");
        refunds.save(fronted);
        String tr = fake.chargeTransfer.get(chargeOf(o));
        fake.transfers.put(tr, new long[] {1_025L, 0L});

        service.recoverForOrg(org.getId());

        // gross_s(574) = 512, fee_s(74) = 66
        assertThat(fake.reversalCreates).hasSize(1);
        assertThat(fake.reversalCreates.get(0).amount()).isEqualTo(446L);
        assertThat(fake.reversalCreates.get(0).key()).isEqualTo("refund:" + fronted.getId() + ":reversal");
        assertThat(refunds.findById(fronted.getId()).orElseThrow().getRecoveredAt()).isNotNull();
    }

    @Test
    void worked_W5_refund_then_lost_reverses_446_eur() {
        Order o = usdOrder(event());
        Refund r = refund(o, 574, 74, false);
        r.setCurrency("usd");
        refunds.save(r);
        Dispute d = lostUsd(o, 1_149, 1_025);
        // the refund's reverse_transfer took gross_s(574) = 512 of the transfer
        fake.transfers.get(fake.chargeTransfer.get(d.getStripeChargeId()))[1] = 512L;

        service.recoverForOrg(org.getId());

        // DisputeShare (575, 75, 500) → 513 − 67 = 446, stake 446, 513 left on the transfer
        assertThat(fake.reversalCreates).extracting(Call::amount).containsExactly(446L);
        assertThat(reload(d).getRecoveredAt()).isNotNull();
    }

    @Test
    void a_transfer_in_another_currency_than_the_settlement_is_never_reversed() {
        Order o = usdOrder(event());
        Dispute d = lostUsd(o, 1_149, 1_025);
        // The dispute and the transfer agree (usd); the order settled in eur, so 892 is not usd units.
        fake.transferCurrency.put(fake.chargeTransfer.get(d.getStripeChargeId()), "usd");

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).isEmpty();
        assertThat(reload(d).getRecoveredAt()).as("the debt stays open").isNull();
    }

    @Test
    void a_platform_funded_refund_on_a_transfer_in_another_currency_is_never_reversed() {
        Order o = usdOrder(event());
        Refund fronted = refund(o, 574, 74, true);
        fronted.setCurrency("usd");
        refunds.save(fronted);
        fake.transferCurrency.put(fake.chargeTransfer.get(chargeOf(o)), "usd");

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).isEmpty();
        assertThat(refunds.findById(fronted.getId()).orElseThrow().getRecoveredAt()).isNull();
    }

    @Test
    void an_unstamped_order_is_never_reversed() {
        // Other key mode and no PaymentIntent: the stamper cannot fill it.
        Order o = order(event(), 1_149, 149, "usd", true, null, null, null);
        Dispute d = lostUsd(o, 1_149, 1_025);
        Refund fronted = refund(o, 574, 74, true);
        fronted.setCurrency("usd");
        refunds.save(fronted);

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).isEmpty();
        assertThat(reload(d).getRecoveredAt()).isNull();
        assertThat(refunds.findById(fronted.getId()).orElseThrow().getRecoveredAt()).isNull();
    }

    @Test
    void a_won_dispute_on_an_unstamped_order_returns_nothing() {
        // Other key mode and no PaymentIntent: the stamper cannot fill it.
        Order o = order(event(), 1_149, 149, "usd", true, null, null, null);
        Dispute won = dispute(o, 1_149, DisputeStatus.WON, Instant.now());
        won.setCurrency("usd");
        disputes.save(won);
        marker.markRecovered(won.getId(), 0L, 892L, "trr_old", true);

        assertThatCode(() -> service.recoverForOrg(org.getId())).doesNotThrowAnyException();

        assertThat(fake.transferCreates).isEmpty();
        assertThat(fake.recoveryReads).as("refused before the return lookup").doesNotContain("/v1/transfers");
        assertThat(disputes.returnedMinorById(won.getId())).isZero();
    }

    @Test
    void a_lost_sibling_of_an_unstamped_return_waits_for_the_next_pass() {
        Order o = order(event(), 1_149, 149, "usd", true, null, null, null);
        Dispute won = dispute(o, 1_149, DisputeStatus.WON, Instant.now());
        won.setCurrency("usd");
        disputes.save(won);
        marker.markRecovered(won.getId(), 0L, 892L, "trr_old", true);
        Dispute sibling = lostUsd(o, 1_149, 1_025);
        Logger logger = (Logger) LoggerFactory.getLogger(PostEventPayoutService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            service.recoverForOrg(org.getId());
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(fake.transferCreates).isEmpty();
        assertThat(fake.reversalCreates).isEmpty();
        assertThat(reload(sibling).getRecoveredAt()).isNull();
        assertThat(logs.list).filteredOn(l -> l.getFormattedMessage().contains(sibling.getId().toString()))
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement().asString().contains("left for the next pass");
    }

    @Test
    void a_platform_funded_refund_whose_order_is_missing_stays_open() {
        Order o = usdOrder(event());
        Refund fronted = refund(o, 574, 74, true);
        doReturn(java.util.Optional.empty()).when(orders).findById(o.getId());

        assertThatCode(() -> service.recoverForOrg(org.getId())).doesNotThrowAnyException();

        assertThat(fake.recoveryReads).as("nothing read for a refund that cannot be sized").isEmpty();
        assertThat(fake.reversalCreates).isEmpty();
        assertThat(refunds.findById(fronted.getId()).orElseThrow().getRecoveredAt()).isNull();
    }

    @Test
    void recovery_for_an_org_without_candidates_stamps_first() {
        Order o = order(event(), 1_149, 149, "usd", false, null, null, null);
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        o = orders.save(o);
        fake.paymentIntents.put(o.getStripePaymentIntentId(),
                OrderSettlementStamperTest.usdProbe(o.getStripePaymentIntentId()));
        Dispute d = lostUsd(o, 1_149, 1_025);

        service.recoverForOrg(org.getId());

        assertThat(fake.paymentIntentReads).containsExactly(o.getStripePaymentIntentId());
        assertThat(fake.reversalCreates).extracting(Call::amount).containsExactly(892L);
        assertThat(reload(d).getRecoveredMinor()).isEqualTo(892L);
    }

    @Test
    void a_timed_out_reversal_stops_the_orders_other_disputes_for_the_rest_of_the_pass() {
        Order o = order(event(), 2_298, 298);
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        Dispute first = dispute(o, 1_149, DisputeStatus.LOST, t);
        Dispute second = dispute(o, 1_149, DisputeStatus.LOST, t.plusSeconds(60));
        fake.transfers.put(fake.chargeTransfer.get(first.getStripeChargeId()), new long[] {2_298L, 0L});
        fake.reversalFailure = "timeout_after";

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).as("the order's share 2000 went out once, the answer was lost")
                .extracting(Call::amount).containsExactly(2_000L);
        assertThat(reload(second).getRecoveredAt()).isNull();

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).hasSize(1);
        assertThat(reload(first).getRecoveredMinor()).isEqualTo(2_000L);
        assertThat(reload(second).getRecoveredMinor()).isZero();
    }

    // ── transfer back on a later win ────────────────────────────────────────────

    @Test
    void a_recovered_share_goes_back_once_the_dispute_is_won() {
        Dispute d = recoveredThen(DisputeStatus.WON);

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).hasSize(1);
        Call c = fake.transferCreates.get(0);
        assertThat(c.amount()).isEqualTo(1_000L);
        assertThat(c.params()).containsEntry("currency", "eur").containsEntry("destination", org.getStripeAccountId());
        assertThat(c.params().get("metadata")).isEqualTo(Map.of("dispute_return_id", d.getId().toString(),
                "dispute_returned_before", "0", "event_id", d.getEventId().toString()));
        assertThat(c.key()).isEqualTo("dispute:" + d.getId() + ":return:0:1000");
        Dispute r = reload(d);
        assertThat(r.getReturnedAt()).isNotNull();
        assertThat(r.getReturnedMinor()).isEqualTo(1_000L);
        assertThat(r.getReturnTransferId()).isEqualTo("tr_back_1");
        assertThat(fake.reversalCreates).isEmpty();
        assertThat(fake.transferListParams).hasSize(1);
        assertThat(fake.transferListParams.get(0)).containsEntry("destination", org.getStripeAccountId());
        assertThat(fake.transferListParams.get(0).get("created"))
                .isEqualTo(Map.of("gte", r.getRecoveredAt().getEpochSecond() - 60L));
    }

    @Test
    void an_open_sibling_on_the_order_keeps_the_share() {
        Dispute d = recoveredThen(DisputeStatus.WON);
        dispute(orders.findById(d.getOrderId()).orElseThrow(), 1_149, DisputeStatus.OPEN, Instant.now());

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).isEmpty();
        assertThat(reload(d).getReturnedAt()).isNull();
    }

    @Test
    void a_won_dispute_returns_only_what_a_lost_sibling_does_not_owe() {
        Dispute d = recoveredThen(DisputeStatus.WON);     // order 1149/149, 1000 recovered
        Dispute sibling = dispute(orders.findById(d.getOrderId()).orElseThrow(), 500, DisputeStatus.LOST,
                Instant.now());

        service.recoverForOrg(org.getId());   // the unreversed sibling holds the return; it is sized at 0
        assertThat(fake.transferCreates).isEmpty();
        service.recoverForOrg(org.getId());

        // sibling share: gross 500, fee round(149 × 500 / 1149) = 65, share 435, already held → 1000 − 435
        assertThat(fake.reversalCreates).isEmpty();
        assertThat(reload(sibling).getRecoveredMinor()).isZero();
        assertThat(fake.transferCreates).extracting(Call::amount).containsExactly(565L);
        assertThat(fake.transferCreates.get(0).key()).isEqualTo("dispute:" + d.getId() + ":return:0:565");
        assertThat(reload(d).getReturnedMinor()).isEqualTo(565L);
    }

    @Test
    void failure_A_a_third_dispute_lost_after_a_partial_return_is_capped_and_the_rest_stays_owed() {
        Dispute d = recoveredThen(DisputeStatus.WON);     // order 1149/149, 1000 recovered, 149 left on the transfer
        Order o = orders.findById(d.getOrderId()).orElseThrow();
        dispute(o, 500, DisputeStatus.LOST, Instant.now());
        service.recoverForOrg(org.getId());
        service.recoverForOrg(org.getId());              // returns 565, 435 still held
        Dispute third = dispute(o, 300, DisputeStatus.LOST, Instant.now());

        service.recoverForOrg(org.getId());

        // LOST 500 + 300 = 800: fee round(149 × 800 / 1149) = 104, share 696; held 1000 − 565 = 435; owed 261,
        // but the transfer has only 1149 − 1000 = 149 left
        assertThat(fake.reversalCreates).extracting(Call::amount).containsExactly(149L);
        assertThat(fake.reversalCreates.get(0).key()).isEqualTo("dispute:" + third.getId() + ":reversal:149");
        Dispute r = reload(third);
        assertThat(r.getRecoveredMinor()).isEqualTo(149L);
        assertThat(r.getRecoveredAt()).as("the 112 short is still an open debt").isNull();
        assertThat(fake.transferCreates).extracting(Call::amount).containsExactly(565L);

        // the next pass reverses nothing twice, and another event's payout holds the 112 back
        Event y = event();
        order(y, 3_300, 300);                            // net 3000
        fake.available = 3_050L;
        service.payOneEvent(y.getId());

        assertThat(fake.reversalCreates).hasSize(1);
        assertThat(reload(third).getRecoveredAt()).isNull();
        assertThat(fake.payouts).extracting(Call::amount).as("min(3000, 3050 − 112)").containsExactly(2_938L);
    }

    @Test
    void failure_B_the_rest_goes_back_when_the_lost_sibling_turns_won() {
        Dispute d = recoveredThen(DisputeStatus.WON);
        Dispute sibling = dispute(orders.findById(d.getOrderId()).orElseThrow(), 500, DisputeStatus.LOST,
                Instant.now());
        service.recoverForOrg(org.getId());
        service.recoverForOrg(org.getId());              // returns 565
        sibling = reload(sibling);
        sibling.setStatus(DisputeStatus.WON);
        disputes.save(sibling);

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).extracting(Call::amount).containsExactly(565L, 435L);
        assertThat(fake.transferCreates.get(1).key()).isEqualTo("dispute:" + d.getId() + ":return:565:435");
        assertThat(reload(d).getReturnedMinor()).isEqualTo(1_000L);

        service.recoverForOrg(org.getId());
        assertThat(fake.transferCreates).as("nothing is held any more").hasSize(2);
    }

    @Test
    void two_lost_recovered_in_one_pass_then_the_one_holding_nothing_wins_returns_the_excess() {
        Order o = order(event(), 2_298, 298);
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        Dispute holder = dispute(o, 1_149, DisputeStatus.LOST, t);
        Dispute other = dispute(o, 1_149, DisputeStatus.LOST, t.plusSeconds(60));
        fake.transfers.put(fake.chargeTransfer.get(holder.getStripeChargeId()), new long[] {2_298L, 0L});
        service.recoverForOrg(org.getId());
        assertThat(reload(holder).getRecoveredMinor()).as("the whole order's share 2000").isEqualTo(2_000L);
        assertThat(reload(other).getRecoveredMinor()).isZero();
        other = reload(other);
        other.setStatus(DisputeStatus.WON);
        disputes.save(other);

        service.recoverForOrg(org.getId());

        // held 2000 − share of the LOST 1149 on 2298/298 (fee round(298 × 1149 / 2298) = 149, share 1000)
        assertThat(fake.transferCreates).extracting(Call::amount).containsExactly(1_000L);
        assertThat(fake.transferCreates.get(0).key()).isEqualTo("dispute:" + holder.getId() + ":return:0:1000");
        assertThat(reload(holder).getReturnedMinor()).isEqualTo(1_000L);
    }

    @Test
    void two_lost_recovered_in_one_pass_then_the_holder_wins_returns_the_excess() {
        Order o = order(event(), 2_298, 298);
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        Dispute holder = dispute(o, 1_149, DisputeStatus.LOST, t);
        dispute(o, 1_149, DisputeStatus.LOST, t.plusSeconds(60));
        fake.transfers.put(fake.chargeTransfer.get(holder.getStripeChargeId()), new long[] {2_298L, 0L});
        service.recoverForOrg(org.getId());
        holder = reload(holder);
        holder.setStatus(DisputeStatus.WON);
        disputes.save(holder);

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).extracting(Call::amount).containsExactly(1_000L);
        assertThat(reload(holder).getReturnedMinor()).isEqualTo(1_000L);
    }

    @Test
    void an_adopted_timed_out_return_reopens_a_new_lost_siblings_debt() {
        Dispute d = recoveredThen(DisputeStatus.WON);     // order 1149/149, 1000 recovered
        fake.transferFailure = "timeout_after";
        service.recoverForOrg(org.getId());              // 1000 sent, the answer lost
        assertThat(reload(d).getReturnedMinor()).isNull();
        Dispute sibling = dispute(orders.findById(d.getOrderId()).orElseThrow(), 500, DisputeStatus.LOST,
                Instant.now());

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).as("adopted, not sent again").hasSize(1);
        assertThat(reload(d).getReturnedMinor()).isEqualTo(1_000L);
        // nothing held any more, so the sibling owes its full share: 500 − round(149 × 500 / 1149) = 435,
        // capped at the 149 the transfer has left; 286 stays owed
        assertThat(fake.reversalCreates).extracting(Call::amount).containsExactly(149L);
        Dispute r = reload(sibling);
        assertThat(r.getRecoveredMinor()).isEqualTo(149L);
        assertThat(r.getRecoveredAt()).isNull();
        Order o = orders.findById(d.getOrderId()).orElseThrow();
        assertThat(withholding.owedOnOrder(o)).isEqualTo(286L);
    }

    @Test
    void a_timed_out_return_is_adopted_even_when_nothing_looks_returnable_any_more() {
        Dispute d = recoveredThen(DisputeStatus.WON);     // order 1149/149, 1000 recovered
        fake.transferFailure = "timeout_after";
        service.recoverForOrg(org.getId());              // 1000 sent, the answer lost
        Dispute sibling = dispute(orders.findById(d.getOrderId()).orElseThrow(), 1_149, DisputeStatus.LOST,
                Instant.now());                          // share 1149 − 149 = 1000: as held, nothing looks returnable

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).as("adopted, not sent again").hasSize(1);
        assertThat(reload(d).getReturnedMinor()).isEqualTo(1_000L);
        Order o = orders.findById(d.getOrderId()).orElseThrow();
        // held 0, so the sibling owes its 1000, capped at the 149 the transfer has left
        assertThat(fake.reversalCreates).extracting(Call::amount).containsExactly(149L);
        assertThat(reload(sibling).getRecoveredAt()).isNull();
        assertThat(withholding.owedOnOrder(o)).isEqualTo(851L);
    }

    @Test
    void an_existing_reversal_past_the_first_page_is_adopted() {
        Order o = order(event(), 1_149, 149);
        Dispute d = lost(o, 1_149, 1_149);
        String tr = fake.chargeTransfer.get(d.getStripeChargeId());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(Map.of("object", "transfer_reversal", "id", "trr_other_" + i, "amount", 1,
                    "metadata", Map.of("dispute_id", UUID.randomUUID().toString())));
        }
        rows.add(Map.of("object", "transfer_reversal", "id", "trr_old", "amount", 1_000,
                "metadata", Map.of("dispute_id", d.getId().toString())));
        fake.reversals.put(tr, rows);

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCreates).isEmpty();
        assertThat(reload(d).getRecoveryReversalId()).isEqualTo("trr_old");
    }

    @Test
    void a_return_transfer_past_the_first_page_of_transfers_is_still_found() {
        Dispute d = recoveredThen(DisputeStatus.WON);
        List<Map<String, Object>> first = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            first.add(Map.of("object", "transfer", "id", "tr_other_" + i, "amount", 1,
                    "metadata", Map.of("dispute_return_id", UUID.randomUUID().toString())));
        }
        fake.platformTransfers.addAll(first);
        fake.platformTransfers.add(Map.of("object", "transfer", "id", "tr_back_old", "amount", 1_000,
                "metadata", Map.of("dispute_return_id", d.getId().toString(), "dispute_returned_before", "0")));

        service.recoverForOrg(org.getId());

        assertThat(fake.transferListParams).hasSize(2);
        assertThat(fake.transferListParams.get(1)).containsEntry("starting_after", "tr_other_99");
        assertThat(fake.transferCreates).isEmpty();
        assertThat(reload(d).getReturnTransferId()).isEqualTo("tr_back_old");
    }

    @Test
    void another_error_on_the_return_leaves_it_for_the_next_pass() {
        Dispute d = recoveredThen(DisputeStatus.WON);
        fake.transferFailure = "server";

        assertThatCode(() -> service.recoverForOrg(org.getId())).doesNotThrowAnyException();

        assertThat(fake.transferCreates).hasSize(1);
        assertThat(reload(d).getReturnedAt()).isNull();
    }

    @Test
    void a_lost_sibling_on_the_order_keeps_the_share() {
        Dispute d = recoveredThen(DisputeStatus.WITHDRAWN_REINSTATED);
        Dispute sibling = dispute(orders.findById(d.getOrderId()).orElseThrow(), 1_149, DisputeStatus.LOST,
                Instant.now());
        marker.markRecovered(sibling.getId(), 0L, 0L, null, true);

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).isEmpty();
        assertThat(reload(d).getReturnedAt()).isNull();
    }

    @Test
    void an_already_returned_share_is_not_sent_twice() {
        Dispute d = recoveredThen(DisputeStatus.WON);
        marker.markReturned(d.getId(), "tr_back_old", 0L, 1_000L);

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).isEmpty();
    }

    @Test
    void an_existing_return_transfer_is_adopted() {
        Dispute d = recoveredThen(DisputeStatus.WON);
        fake.platformTransfers.add(Map.of("object", "transfer", "id", "tr_back_old", "amount", 1_000,
                "metadata", Map.of("dispute_return_id", d.getId().toString(), "dispute_returned_before", "0")));

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).isEmpty();
        assertThat(reload(d).getReturnTransferId()).isEqualTo("tr_back_old");
        assertThat(reload(d).getReturnedMinor()).isEqualTo(1_000L);
    }

    @Test
    void a_short_platform_balance_leaves_the_return_for_the_next_pass() {
        Dispute d = recoveredThen(DisputeStatus.WON);
        fake.transferFailure = "balance_insufficient";

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).hasSize(1);
        assertThat(reload(d).getReturnedAt()).isNull();
    }

    @Test
    void an_org_without_a_connected_account_is_never_sent_a_transfer() {
        Dispute d = recoveredThen(DisputeStatus.WON);
        org.setStripeAccountId(null);
        orgs.save(org);

        service.recoverForOrg(org.getId());

        assertThat(fake.transferCreates).isEmpty();
        assertThat(reload(d).getReturnedAt()).isNull();
    }

    // ── the hold on the org's other payouts ─────────────────────────────────────

    @Test
    void an_unrecovered_debt_on_another_event_is_held_back_only_when_the_balance_needs_it() {
        Event x = event();
        lost(order(x, 1_149, 149), 1_149, 1_149);
        Event y = event();
        order(y, 3_300, 300);                     // net 3000
        fake.reversalFailure = "balance_insufficient";
        fake.available = 5_000L;

        service.payOneEvent(y.getId());

        assertThat(fake.payouts).extracting(Call::amount).as("min(3000, 5000 − 1000)").containsExactly(3_000L);
    }

    @Test
    void the_events_own_lost_share_is_not_held_back_twice() {
        Event y = event();
        order(y, 3_300, 300);
        lost(order(y, 1_149, 149), 1_149, 1_149);  // net 4449 − 449 − 1000 = 3000
        fake.reversalFailure = "balance_insufficient";
        fake.available = 3_500L;

        service.payOneEvent(y.getId());

        assertThat(fake.payouts).extracting(Call::amount).containsExactly(3_000L);
    }

    @Test
    void a_recovered_debt_is_not_held_back() {
        Event x = event();
        lost(order(x, 1_149, 149), 1_149, 1_149);
        Event y = event();
        order(y, 3_300, 300);
        fake.available = 3_500L;

        service.payOneEvent(y.getId());

        assertThat(fake.reversalCreates).hasSize(1);
        assertThat(fake.payouts).extracting(Call::amount).containsExactly(3_000L);
    }

    @Test
    void a_usd_order_settled_in_eur_is_held_back_from_the_eur_payout() {
        Event x = event();
        x.setCurrency("USD");
        events.save(x);
        Dispute d = lostUsd(usdOrder(x), 1_149, 1_025);
        fake.reversalFailure = "balance_insufficient";
        Event y = event();
        order(y, 3_300, 300);
        fake.available = 3_500L;

        service.payOneEvent(y.getId());

        assertThat(reload(d).getRecoveredAt()).isNull();
        assertThat(fake.payouts).extracting(Call::amount).as("min(3000, 3500 − 892)").containsExactly(2_608L);
    }

    @Test
    void a_debt_in_another_currency_is_not_held_back() {
        Event x = event();
        x.setCurrency("GBP");
        events.save(x);
        Order gbp = order(x, 1_149, 149, "gbp", false, "gbp", 1_149L, 149L);
        Dispute d = lost(gbp, 1_149, 1_149);
        d.setCurrency("gbp");
        disputes.save(d);
        fake.transferCurrency.put(fake.chargeTransfer.get(d.getStripeChargeId()), "gbp");
        fake.reversalFailure = "balance_insufficient";
        Event y = event();
        order(y, 3_300, 300);
        fake.available = 3_500L;

        service.payOneEvent(y.getId());

        assertThat(reload(d).getRecoveredAt()).isNull();
        assertThat(fake.payouts).extracting(Call::amount).as("a GBP debt is not in the EUR balance")
                .containsExactly(3_000L);
    }

    @Test
    void a_test_mode_debt_is_not_held_back() {
        Event x = event();
        Dispute d = lost(order(x, 1_149, 149), 1_149, 1_149);
        d.setTestMode(true);
        disputes.save(d);
        Event y = event();
        order(y, 3_300, 300);
        fake.available = 3_500L;

        service.payOneEvent(y.getId());

        assertThat(fake.payouts).extracting(Call::amount).containsExactly(3_000L);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private Organization org(String acct) {
        Organization o = new Organization();
        o.setName("Dispute Org");
        o.setSlug("dispute-org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("o@test.example");
        o.setCountry("FR");
        o.setStripeAccountId(acct);
        o.setStripeConnectState(StripeConnectState.ACTIVE);
        o.setStripePayoutsEnabled(true);
        o.setStripePayoutScheduleManual(true);
        return orgs.save(o);
    }

    private Event event() {
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
        e.setCurrency("EUR");
        e.setEndsAt(Instant.now().minus(10, ChronoUnit.DAYS));
        e.setCreatedBy(userId);
        return events.save(e);
    }

    /** An EUR order, settled 1:1 as V174 stamps every EUR order. */
    private Order order(Event e, long totalMinor, long feeMinor) {
        return order(e, totalMinor, feeMinor, "eur", false, "eur", totalMinor, feeMinor);
    }

    /** The sandbox order: 1149 USD, fee 149, settled as 1025 EUR with a 133 EUR fee. */
    private Order usdOrder(Event e) {
        return order(e, 1_149, 149, "usd", false, "eur", 1_025L, 133L);
    }

    /** Stamped at insert ({@code sCur} null = unstamped), as the settlement columns are never updatable. */
    private Order order(Event e, long totalMinor, long feeMinor, String currency, boolean testMode,
                        String sCur, Long settledGross, Long settledFee) {
        Order o = new Order();
        o.setToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(totalMinor);
        o.setCurrency(currency);
        o.setApplicationFeeMinor(feeMinor);
        o.setPaymentMethod("card");
        o.setTestMode(testMode);
        o.setSettlementCurrency(sCur);
        o.setSettlementGrossMinor(settledGross);
        o.setSettlementFeeMinor(settledFee);
        return orders.save(o);
    }

    /** {@link #lost} in USD, the presentment currency of {@link #usdOrder}. */
    private Dispute lostUsd(Order o, long amountMinor, long transferMinor) {
        Dispute d = lost(o, amountMinor, transferMinor);
        d.setCurrency("usd");
        return disputes.save(d);
    }

    private Refund refund(Order o, long amountMinor, long feeMinor, boolean platformFunded) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        // The refund and the dispute share the order's charge, so its transfer too.
        r.setStripeChargeId(chargeOf(o));
        r.setAmountMinor(amountMinor);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(feeMinor);
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setPlatformFunded(platformFunded);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        return refunds.save(r);
    }

    /** A LOST dispute on the order's charge, whose destination transfer carries {@code transferMinor}. */
    private Dispute lost(Order o, long amountMinor, long transferMinor) {
        Dispute d = dispute(o, amountMinor, DisputeStatus.LOST, Instant.now());
        fake.transfers.putIfAbsent(fake.chargeTransfer.get(d.getStripeChargeId()), new long[] {transferMinor, 0L});
        return d;
    }

    private Dispute dispute(Order o, long amountMinor, DisputeStatus status, Instant createdAt) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        d.setOrgId(org.getId());
        if (o != null) {
            d.setEventId(o.getEventId());
            d.setOrderId(o.getId());
            d.setStripeChargeId(chargeOf(o));
        } else {
            d.setStripeChargeId("ch_orphan_" + (++seq));
            fake.chargeTransfer.put(d.getStripeChargeId(), "tr_orphan_" + seq);
        }
        d.setAmountMinor(amountMinor);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setCreatedAt(createdAt);
        return disputes.save(d);
    }

    /** One charge and one destination transfer per order. */
    private String chargeOf(Order o) {
        String ch = "ch_" + o.getId().toString().replace("-", "").substring(0, 20);
        fake.chargeTransfer.putIfAbsent(ch, "tr_" + o.getId().toString().replace("-", "").substring(0, 20));
        return ch;
    }

    /** A worked-A dispute whose 1000 was reversed, now closed in the organizer's favour. */
    private Dispute recoveredThen(DisputeStatus status) {
        Dispute d = dispute(order(event(), 1_149, 149), 1_149, status, Instant.now());
        // That reversal took 1000 of the 1149 transfer: 149 is left to reverse, and a return never refills it.
        fake.transfers.putIfAbsent(fake.chargeTransfer.get(d.getStripeChargeId()), new long[] {1_149L, 1_000L});
        marker.markRecovered(d.getId(), 0L, 1_000L, "trr_old", true);
        return d;
    }

    private Dispute reload(Dispute d) {
        return disputes.findById(d.getId()).orElseThrow();
    }
}
