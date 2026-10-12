package com.imin.iminapi.cancellation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.UserRepository;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cancel, preview and status: roles, org scoping, the plan-changed guard, and buckets that add up. */
@IminIntegrationTest
class EventCancellationControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditRows audit;
    @Autowired MutableClock clock;
    @Autowired UserRepository users;
    @Autowired StripeClient stripeClient;
    @Autowired EventCancellationSweeper sweeper;
    final ObjectMapper om = new ObjectMapper();

    private final List<UUID> orgIds = new ArrayList<>();
    private Organization org;
    private User owner;
    private AuthPrincipal principal;
    private Event event;
    private TicketTier tier;

    @BeforeEach
    void setUp() {
        org = fx.org();
        orgIds.add(org.getId());
        owner = fx.owner(org);
        principal = fx.principal(owner);
        event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(10)));
        tier = fx.tier(event, 2000, 100);
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void cancel_stopsSalesEnrollsEveryOrderAuditsAndIsIdempotent() throws Exception {
        Order a = paidOrder(2298, 298);
        Order b = paidOrder(1149, 149);
        Order free = freeOrder();

        cancel(principal, event.getId(), Map.of("confirm", true, "expectedOrders", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(event.getId().toString()))
                .andExpect(jsonPath("$.orders.total").value(3))
                // Enrolled orders wait for the sweeper, the free one included, until it derives their state.
                .andExpect(jsonPath("$.orders.processing").value(3))
                .andExpect(jsonPath("$.orders.free").value(0));

        assertThat(eventStatus()).isEqualTo("CANCELLED");
        assertThat(items()).containsExactlyInAnyOrderEntriesOf(Map.of(
                a.getId(), "pending", b.getId(), "pending", free.getId(), "pending"));
        audit.assertRecorded(org.getId(), "EVENT_CANCELLED", "event", event.getId());
        String cancelledAt = jdbc.queryForObject(
                "SELECT cancelled_at::text FROM event_cancellations WHERE event_id = ?", String.class, event.getId());

        // A second click answers the same cancellation and writes nothing.
        cancel(principal, event.getId(), Map.of("confirm", true, "expectedOrders", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.total").value(3));
        assertThat(items()).hasSize(3);
        assertThat(jdbc.queryForObject(
                "SELECT cancelled_at::text FROM event_cancellations WHERE event_id = ?", String.class, event.getId()))
                .isEqualTo(cancelledAt);
        audit.assertRecorded(org.getId(), "EVENT_CANCELLED", "event", event.getId());
    }

    enum Refusal { MEMBER, OTHER_ORG, DRAFT, ENDED, STALE_PREVIEW, NOT_CONFIRMED }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Refusal.class)
    void cancel_refusals(Refusal refusal) throws Exception {
        paidOrder(2298, 298);
        paidOrder(1149, 149);
        AuthPrincipal caller = principal;
        Map<String, Object> body = Map.of("confirm", true, "expectedOrders", 2);
        String before = "LIVE";
        switch (refusal) {
            case MEMBER -> caller = fx.principal(member());
            case OTHER_ORG -> {
                Organization other = fx.org();
                orgIds.add(other.getId());
                caller = fx.principal(fx.owner(other));
            }
            case DRAFT -> {
                jdbc.update("UPDATE events SET status = 'DRAFT' WHERE id = ?", event.getId());
                before = "DRAFT";
            }
            case ENDED -> jdbc.update("UPDATE events SET starts_at = ?, ends_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant().minus(Duration.ofHours(5))),
                    Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))), event.getId());
            // A buyer paid while the dialog was open: the organizer confirmed 1 order, there are 2.
            case STALE_PREVIEW -> body = Map.of("confirm", true, "expectedOrders", 1);
            case NOT_CONFIRMED -> body = Map.of("confirm", false, "expectedOrders", 2);
        }

        ResultActions res = cancel(caller, event.getId(), body);
        switch (refusal) {
            case MEMBER -> res.andExpect(status().isForbidden());
            case OTHER_ORG -> res.andExpect(status().isNotFound());
            case DRAFT, ENDED -> res.andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
            case STALE_PREVIEW -> res.andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("EVENT_CANCEL_PLAN_CHANGED"))
                    .andExpect(jsonPath("$.error.fields.orders").value("2"));
            case NOT_CONFIRMED -> res.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                    .andExpect(jsonPath("$.error.fields.confirm").value("required"));
        }
        assertThat(eventStatus()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_cancellations WHERE event_id = ?",
                Integer.class, event.getId())).isZero();
        assertThat(items()).isEmpty();
    }

    @Test
    void preview_countsAndTotals() throws Exception {
        paidOrder(2298, 298);
        freeOrder();
        Order disputed = paidOrder(1149, 149);
        jdbc.update("""
                INSERT INTO disputes (id, stripe_dispute_id, org_id, event_id, order_id, amount_minor, currency,
                    status, test_mode, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1149, 'eur', 'open', true, now(), now())
                """, UUID.randomUUID(), "du_" + UUID.randomUUID().toString().substring(0, 12), org.getId(),
                event.getId(), disputed.getId());
        Order refunded = paidOrder(1149, 149);
        jdbc.update("""
                INSERT INTO refunds (id, order_id, stripe_payment_intent_id, amount_minor, currency,
                    application_fee_refund_minor, reason, status, initiated_by_user_id, idempotency_key)
                VALUES (?, ?, 'pi_x', 1149, 'EUR', 149, 'OTHER', 'SUCCEEDED', ?, ?)
                """, UUID.randomUUID(), refunded.getId(), owner.getId(), "k-" + UUID.randomUUID());
        // Paid under the other Stripe key: the running key cannot refund it, so it is not in the plan.
        Order otherMode = paidOrder(1149, 149);
        jdbc.update("UPDATE orders SET test_mode = false WHERE id = ?", otherMode.getId());
        jdbc.update("""
                INSERT INTO ticket_reservations (id, tier_id, qty, status, created_at, expires_at)
                VALUES (?, ?, 1, 'HELD', now(), ?)
                """, UUID.randomUUID(), tier.getId(), Timestamp.from(clock.instant().plus(Duration.ofMinutes(20))));

        mvc.perform(get("/api/v1/events/{id}/cancellation/preview", event.getId()).with(auth(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(4))
                .andExpect(jsonPath("$.paidToRefund").value(1))
                .andExpect(jsonPath("$.alreadyRefunded").value(1))
                .andExpect(jsonPath("$.disputed").value(1))
                .andExpect(jsonPath("$.free").value(1))
                .andExpect(jsonPath("$.checkoutsInProgress").value(1))
                .andExpect(jsonPath("$.totals.length()").value(1))
                .andExpect(jsonPath("$.totals[0].currency").value("EUR"))
                .andExpect(jsonPath("$.totals[0].refundMinor").value(2298))
                .andExpect(jsonPath("$.totals[0].bookingFeeMinor").value(298))
                .andExpect(jsonPath("$.totals[0].ticketPriceMinor").value(2000));

        // Any member may read it; another org gets the same 404 as a missing event.
        mvc.perform(get("/api/v1/events/{id}/cancellation/preview", event.getId()).with(auth(fx.principal(member()))))
                .andExpect(status().isOk());
        Organization other = fx.org();
        orgIds.add(other.getId());
        mvc.perform(get("/api/v1/events/{id}/cancellation/preview", event.getId())
                        .with(auth(fx.principal(fx.owner(other)))))
                .andExpect(status().isNotFound());
    }

    @Test
    void status_bucketsSumToTotal_andOverdueAfterThreeDays() throws Exception {
        // 23:30Z is already the next day in Paris (UTC+1 in November); overdue must stay a 72 h span, not a day count.
        clock.setInstant(Instant.parse("2026-11-03T23:30:00Z"));
        jdbc.update("UPDATE events SET starts_at = ?, ends_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofDays(10))),
                Timestamp.from(clock.instant().plus(Duration.ofDays(10).plusHours(4))), event.getId());
        Order ok = paidOrder(1149, 149);
        Order refused = paidOrder(2298, 298);
        freeOrder();
        Order disputed = paidOrder(1149, 149);
        jdbc.update("""
                INSERT INTO disputes (id, stripe_dispute_id, org_id, event_id, order_id, amount_minor, currency,
                    status, test_mode, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1149, 'eur', 'open', true, now(), now())
                """, UUID.randomUUID(), "du_" + UUID.randomUUID().toString().substring(0, 12), org.getId(),
                event.getId(), disputed.getId());
        com.stripe.service.RefundService stripeRefunds = mock(com.stripe.service.RefundService.class);
        when(stripeClient.refunds()).thenReturn(stripeRefunds);
        when(stripeRefunds.create(argThat((RefundCreateParams p) -> p != null
                        && ok.getStripePaymentIntentId().equals(p.getPaymentIntent())), any(RequestOptions.class)))
                .thenAnswer(inv -> stripeRefund("pending"));
        when(stripeRefunds.create(argThat((RefundCreateParams p) -> p != null
                        && !ok.getStripePaymentIntentId().equals(p.getPaymentIntent())), any(RequestOptions.class)))
                .thenThrow(new InvalidRequestException("No such payment_intent", "payment_intent", "req_1",
                        "resource_missing", 400, null));

        cancel(principal, event.getId(), Map.of("confirm", true)).andExpect(status().isOk());
        tick();

        mvc.perform(get("/api/v1/events/{id}/cancellation", event.getId()).with(auth(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").value("2026-11-03T23:30:00Z"))
                .andExpect(jsonPath("$.orders.total").value(4))
                .andExpect(jsonPath("$.orders.refunded").value(1))
                .andExpect(jsonPath("$.orders.processing").value(1))
                .andExpect(jsonPath("$.orders.waitingOnDispute").value(1))
                .andExpect(jsonPath("$.orders.free").value(1))
                .andExpect(jsonPath("$.orders.settledByBank").value(0))
                .andExpect(jsonPath("$.orders.needsAttention").value(0))
                .andExpect(jsonPath("$.overdue").value(0))
                .andExpect(jsonPath("$.buyersEmailed").value(4))
                .andExpect(jsonPath("$.buyersToEmail").value(4));

        // Past 3 days: the refused order is overdue, and so is one paid late, counted from the cancel itself.
        clock.advance(Duration.ofDays(3).plusMinutes(1));
        Order late = paidOrder(1149, 149);
        tick();

        mvc.perform(get("/api/v1/events/{id}/cancellation", event.getId()).with(auth(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.total").value(5))
                .andExpect(jsonPath("$.orders.processing").value(2))
                .andExpect(jsonPath("$.overdue").value(2));
        assertThat(items().get(late.getId())).isEqualTo("pending");
        assertThat(items().get(refused.getId())).isEqualTo("pending");
    }

    @Test
    void status_ofAnEventNeverCancelled_is404() throws Exception {
        mvc.perform(get("/api/v1/events/{id}/cancellation", event.getId()).with(auth(principal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void publish_cancelledEvent_is409() throws Exception {
        cancel(principal, event.getId(), Map.of("confirm", true)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/events/{id}/publish", event.getId()).with(auth(principal)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(eventStatus()).isEqualTo("CANCELLED");
    }

    // ---------- helpers ----------

    private User member() {
        User u = new User();
        u.setEmail(fx.email("member"));
        u.setOrgId(org.getId());
        u.setRole(UserRole.MEMBER);
        return users.save(u);
    }

    private void tick() {
        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = 'event_cancellation_refunds'");
        sweeper.tick();
    }

    private static com.stripe.model.Refund stripeRefund(String status) {
        com.stripe.model.Refund r = new com.stripe.model.Refund();
        r.setId("re_" + UUID.randomUUID().toString().replace("-", ""));
        r.setCharge("ch_" + UUID.randomUUID().toString().replace("-", ""));
        r.setStatus(status);
        return r;
    }

    private Order paidOrder(long totalMinor, long feeMinor) {
        Order o = fx.order(event, fx.email("buyer"));
        String pi = "pi_" + UUID.randomUUID().toString().replace("-", "");
        // test_mode as checkout stamps it under the suite's sk_test key.
        jdbc.update("UPDATE orders SET stripe_payment_intent_id = ?, application_fee_minor = ?, total_minor = ?, "
                        + "test_mode = true WHERE id = ?", pi, feeMinor, totalMinor, o.getId());
        o.setStripePaymentIntentId(pi);
        return o;
    }

    private Order freeOrder() {
        Order o = fx.order(event, fx.email("free"));
        jdbc.update("UPDATE orders SET total_minor = 0, application_fee_minor = 0, test_mode = true, "
                + "payment_method = 'free' WHERE id = ?", o.getId());
        return o;
    }

    private String eventStatus() {
        return jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, event.getId());
    }

    private Map<UUID, String> items() {
        Map<UUID, String> out = new java.util.HashMap<>();
        jdbc.query("SELECT order_id, state FROM event_cancellation_orders WHERE event_id = ?",
                rs -> { out.put(rs.getObject("order_id", UUID.class), rs.getString("state")); }, event.getId());
        return out;
    }

    private ResultActions cancel(AuthPrincipal p, UUID eventId, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/events/{id}/cancel", eventId)
                .with(auth(p))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
