package com.imin.iminapi.dispute;

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
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.util.Times;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The V172 recovery queries and bulk updates on Postgres 17, where H2 is lenient. Org-wide reads assert own ids. */
@IminIntegrationTest
class DisputeRecoveryQueriesPostgresTest {

    private static final List<DisputeStatus> BACK = List.of(DisputeStatus.WON, DisputeStatus.WITHDRAWN_REINSTATED);

    @Autowired DisputeRepository disputes;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired com.imin.iminapi.refund.RefundRepository refunds;
    @Autowired TransactionTemplate tx;

    private final List<UUID> orgIds = new ArrayList<>();

    private Organization org;
    private Event event;

    @BeforeEach
    void setUp() {
        org = org();
        event = event(org);
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void unrecovered_lost_lists_only_lost_attributed_unrecovered_rows_of_the_mode_oldest_first() {
        Order o = order(event);
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        Dispute later = dispute(o, DisputeStatus.LOST, false, t.plusSeconds(60));
        Dispute first = dispute(o, DisputeStatus.LOST, false, t);
        dispute(o, DisputeStatus.OPEN, false, t);
        dispute(o, DisputeStatus.LOST, true, t);
        dispute(null, DisputeStatus.LOST, false, t);
        Dispute recovered = dispute(o, DisputeStatus.LOST, false, t);
        mark(recovered, 500L);
        dispute(order(event(org())), DisputeStatus.LOST, false, t);   // another org

        assertThat(disputes.findUnrecoveredLostByOrgId(org.getId(), DisputeStatus.LOST, false))
                .extracting(Dispute::getId).containsExactly(first.getId(), later.getId());
    }

    @Test
    void return_candidates_are_rows_still_holding_on_orders_with_a_won_or_reinstated_dispute() {
        Order o = order(event);
        Instant t = Instant.now();
        Dispute won = dispute(o, DisputeStatus.WON, false, t);
        mark(won, 1_000L);
        Dispute reinstated = dispute(o, DisputeStatus.WITHDRAWN_REINSTATED, false, t.plusSeconds(1));
        mark(reinstated, 1_000L);
        Dispute zero = dispute(o, DisputeStatus.WON, false, t);
        mark(zero, 0L);
        Dispute returned = dispute(o, DisputeStatus.WON, false, t);
        mark(returned, 1_000L);
        tx.execute(s -> disputes.markReturned(returned.getId(), "tr_back", 0L, 1_000L, Times.nowMicros()));
        Dispute partly = dispute(o, DisputeStatus.WON, false, t.plusSeconds(2));
        mark(partly, 1_000L);
        tx.execute(s -> disputes.markReturned(partly.getId(), "tr_back_p", 0L, 565L, Times.nowMicros()));
        Dispute lostHolder = dispute(o, DisputeStatus.LOST, false, t.plusSeconds(3));
        mark(lostHolder, 1_000L);
        Dispute testMode = dispute(o, DisputeStatus.WON, true, t);
        mark(testMode, 1_000L);
        Order noWin = order(event);
        Dispute lostAlone = dispute(noWin, DisputeStatus.LOST, false, t);
        mark(lostAlone, 1_000L);

        assertThat(disputes.findReturnCandidatesByOrgId(org.getId(), BACK, false)).extracting(Dispute::getId)
                .containsExactly(won.getId(), reinstated.getId(), partly.getId(), lostHolder.getId());
    }

    @Test
    void owing_orgs_cover_both_branches_of_the_or_and_the_mode() {
        Dispute lost = dispute(order(event), DisputeStatus.LOST, false, Instant.now());
        Organization other = org();
        Dispute won = dispute(order(event(other)), DisputeStatus.WON, false, Instant.now());
        mark(won, 1_000L);
        Organization settled = org();
        Dispute done = dispute(order(event(settled)), DisputeStatus.LOST, false, Instant.now());
        mark(done, 1_000L);
        Organization testOrg = org();
        dispute(order(event(testOrg)), DisputeStatus.LOST, true, Instant.now());

        List<UUID> owing = disputes.findOrgIdsOwingDisputeMoney(DisputeStatus.LOST, BACK, false);

        assertThat(owing).contains(org.getId(), other.getId())
                .doesNotContain(settled.getId(), testOrg.getId());
        assertThat(disputes.findOrgIdsOwingDisputeMoney(DisputeStatus.LOST, BACK, true)).contains(testOrg.getId());
        assertThat(lost.getOrgId()).isEqualTo(org.getId());
    }

    @Test
    void order_sums_count_lost_amounts_and_recovered_not_returned() {
        Order o = order(event);
        dispute(o, DisputeStatus.LOST, false, Instant.now(), 700);
        Dispute held = dispute(o, DisputeStatus.LOST, false, Instant.now(), 449);
        mark(held, 300L);
        Dispute back = dispute(o, DisputeStatus.WON, false, Instant.now(), 1_149);
        mark(back, 200L);
        tx.execute(s -> disputes.markReturned(back.getId(), "tr_back", 0L, 150L, Times.nowMicros()));
        dispute(o, DisputeStatus.OPEN, false, Instant.now(), 5_000);

        assertThat(disputes.sumLostAmountByOrderId(o.getId(), DisputeStatus.LOST)).isEqualTo(1_149L);
        assertThat(disputes.sumHeldByOrderId(o.getId())).as("300 + (200 − 150)").isEqualTo(350L);
        assertThat(disputes.returnedMinorById(back.getId())).isEqualTo(150L);
        assertThat(disputes.returnedMinorById(held.getId())).isZero();
        assertThat(disputes.sumLostAmountByOrderId(order(event).getId(), DisputeStatus.LOST)).isZero();
    }

    @Test
    void the_bulk_updates_are_conditional() {
        Dispute d = dispute(order(event), DisputeStatus.LOST, false, Instant.now());

        assertThat(returned(d.getId(), "tr_back", 0L, 500L)).isZero();
        assertThat(recovered(d.getId(), 1_000L, "trr_1")).isEqualTo(1);
        assertThat(recovered(d.getId(), 999L, "trr_2")).isZero();
        assertThat(returned(d.getId(), "tr_back", 0L, 300L)).isEqualTo(1);
        assertThat(returned(d.getId(), "tr_back_again", 0L, 300L)).as("one transfer counts once").isZero();
        assertThat(returned(d.getId(), "tr_back_2", 300L, 701L)).as("never past what was recovered").isZero();
        assertThat(returned(d.getId(), "tr_back_2", 300L, 700L)).isEqualTo(1);

        Dispute r = disputes.findById(d.getId()).orElseThrow();
        assertThat(r.getRecoveredMinor()).isEqualTo(1_000L);
        assertThat(r.getRecoveryReversalId()).isEqualTo("trr_1");
        assertThat(r.getReturnTransferId()).isEqualTo("tr_back_2");
        assertThat(r.getReturnedMinor()).isEqualTo(1_000L);
        assertThat(r.getRecoveredAt()).isNotNull();
        assertThat(r.getReturnedAt()).isNotNull();

        Dispute nothing = dispute(order(event), DisputeStatus.LOST, false, Instant.now());
        assertThat(recovered(nothing.getId(), 0L, null)).isEqualTo(1);
        Dispute z = disputes.findById(nothing.getId()).orElseThrow();
        assertThat(z.getRecoveredAt()).isNotNull();
        assertThat(z.getRecoveredMinor()).isZero();
        assertThat(z.getRecoveryReversalId()).isNull();
    }

    @Test
    void return_blockers_are_open_siblings_and_lost_ones_not_reversed_yet() {
        Order o = order(event);
        Dispute self = dispute(o, DisputeStatus.WON, false, Instant.now());
        Dispute lostDone = dispute(o, DisputeStatus.LOST, false, Instant.now());
        mark(lostDone, 0L);
        dispute(o, DisputeStatus.WITHDRAWN_REINSTATED, false, Instant.now());
        dispute(order(event), DisputeStatus.OPEN, false, Instant.now());   // another order

        assertThat(disputes.countOtherOpenOrUnrecoveredLostByOrderId(o.getId(), self.getId(),
                DisputeStatus.OPEN, DisputeStatus.LOST)).isZero();
        dispute(o, DisputeStatus.LOST, false, Instant.now());
        assertThat(disputes.countOtherOpenOrUnrecoveredLostByOrderId(o.getId(), self.getId(),
                DisputeStatus.OPEN, DisputeStatus.LOST)).isEqualTo(1L);
        dispute(o, DisputeStatus.OPEN, false, Instant.now());
        assertThat(disputes.countOtherOpenOrUnrecoveredLostByOrderId(o.getId(), self.getId(),
                DisputeStatus.OPEN, DisputeStatus.LOST)).isEqualTo(2L);
    }

    @Test
    void the_reserve_rows_leave_out_the_paid_event_recovered_and_test_mode_rows() {
        Event other = event(org);
        Order a = order(other);
        dispute(a, DisputeStatus.LOST, false, Instant.now(), 700);
        dispute(a, DisputeStatus.LOST, false, Instant.now(), 449);
        Dispute recovered = dispute(order(other), DisputeStatus.LOST, false, Instant.now());
        mark(recovered, 1_000L);
        dispute(order(other), DisputeStatus.LOST, true, Instant.now());
        dispute(order(other), DisputeStatus.OPEN, false, Instant.now());
        dispute(order(event), DisputeStatus.LOST, false, Instant.now());   // the event being paid

        Order gbp = order(other, "GBP", "gbp", 1_149L, 149L);
        dispute(gbp, DisputeStatus.LOST, false, Instant.now());

        List<DisputeSettlementRow> rows =
                disputes.unrecoveredLostRowsByOrgExcludingEvent(org.getId(), DisputeStatus.LOST, event.getId(), "eur");

        assertThat(rows).containsExactly(
                new DisputeSettlementRow(other.getId(), a.getId(), 1_149L, 149L, "eur", 1_149L, 149L, 1_149L));
        assertThat(disputes.unrecoveredLostRowsByOrgExcludingEvent(org.getId(), DisputeStatus.LOST,
                event.getId(), "gbp")).extracting(DisputeSettlementRow::orderId).containsExactly(gbp.getId());
    }

    @Test
    void settlement_rows_sum_succeeded_refunds_per_live_order() {
        Order live = order(event, "USD", "eur", 1_025L, 133L);
        Order test = order(event, "EUR", "eur", 1_149L, 149L);
        test.setTestMode(true);
        orders.save(test);
        refund(live, 574, 74, com.imin.iminapi.refund.RefundStatus.SUCCEEDED);
        refund(live, 300, 39, com.imin.iminapi.refund.RefundStatus.FAILED);
        refund(test, 1_149, 149, com.imin.iminapi.refund.RefundStatus.SUCCEEDED);
        Order unrefunded = order(event, "EUR", "eur", 1_149L, 149L);

        assertThat(orders.settlementRowsByEventId(event.getId())).containsExactlyInAnyOrder(
                new com.imin.iminapi.payout.OrderSettlementRow(live.getId(), 1_149L, 149L, "eur", 1_025L, 133L,
                        574L, 74L),
                new com.imin.iminapi.payout.OrderSettlementRow(unrefunded.getId(), 1_149L, 149L, "eur", 1_149L,
                        149L, 0L, 0L));
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private int returned(UUID id, String transferId, long before, long amount) {
        Integer n = tx.execute(s -> disputes.markReturned(id, transferId, before, amount, Times.nowMicros()));
        return n == null ? -1 : n;
    }

    private int recovered(UUID id, long amount, String reversalId) {
        Integer n = tx.execute(s -> disputes.markRecovered(id, 0L, amount, reversalId, Times.nowMicros()));
        return n == null ? -1 : n;
    }

    private void mark(Dispute d, long amount) {
        tx.execute(s -> disputes.markRecovered(d.getId(), 0L, amount, amount == 0 ? null : "trr_" + amount,
                Times.nowMicros()));
    }

    private Organization org() {
        Organization o = new Organization();
        o.setName("Org");
        o.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("o@test.example");
        o.setCountry("FR");
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private Event event(Organization o) {
        User u = new User();
        u.setOrgId(o.getId());
        u.setEmail("u-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        UUID userId = users.save(u).getId();
        Event e = new Event();
        e.setOrgId(o.getId());
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setCurrency("EUR");
        e.setCreatedBy(userId);
        e = events.save(e);
        return e;
    }

    /** An EUR order, settled 1:1 as V174 stamps every EUR order. */
    private Order order(Event e) {
        return order(e, "eur", "eur", 1_149L, 149L);
    }

    /** Stamped at insert, as the settlement columns are never updatable. */
    private Order order(Event e, String currency, String sCur, Long settledGross, Long settledFee) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("b@test.example");
        o.setTotalMinor(1_149);
        o.setApplicationFeeMinor(149);
        o.setCurrency(currency);
        o.setPaymentMethod("card");
        o.setSettlementCurrency(sCur);
        o.setSettlementGrossMinor(settledGross);
        o.setSettlementFeeMinor(settledFee);
        o = orders.save(o);
        return o;
    }

    private Dispute dispute(Order o, DisputeStatus status, boolean testMode, Instant createdAt) {
        return dispute(o, status, testMode, createdAt, 1_149);
    }

    private void refund(Order o, long amountMinor, long feeMinor, com.imin.iminapi.refund.RefundStatus status) {
        com.imin.iminapi.refund.Refund r = new com.imin.iminapi.refund.Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setAmountMinor(amountMinor);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(feeMinor);
        r.setReason(com.imin.iminapi.refund.RefundReason.OTHER);
        r.setStatus(status);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        refunds.save(r);
    }

    private Dispute dispute(Order o, DisputeStatus status, boolean testMode, Instant createdAt, long amount) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        d.setOrgId(o == null ? org.getId() : o.getOrgId());
        if (o != null) {
            d.setEventId(o.getEventId());
            d.setOrderId(o.getId());
        }
        d.setAmountMinor(amount);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setTestMode(testMode);
        d.setCreatedAt(createdAt);
        d = disputes.save(d);
        return d;
    }
}
