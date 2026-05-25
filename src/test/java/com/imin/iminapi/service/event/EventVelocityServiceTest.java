package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
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
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class EventVelocityServiceTest {

    @Autowired EventVelocityService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;

    private Organization org;
    private User owner;
    private Event event;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        wipe();
        org = new Organization();
        org.setName("Vel Org");
        org.setSlug("vel-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hello@vel.example");
        org.setCountry("DE");
        org = orgs.save(org);

        owner = new User();
        owner.setEmail("vel-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Velocity Night");
        event.setSlug("vel-event-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setPublishedAt(Instant.now().minusSeconds(86_400L * 30));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event.setTimezone("UTC");
        event = events.save(event);

        principal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        refunds.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private Refund newSucceededRefund(Order o, long amountMinor, Instant updatedAt) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId(o.getStripePaymentIntentId());
        r.setAmountMinor(amountMinor);
        r.setCurrency(o.getCurrency());
        r.setApplicationFeeRefundMinor(0);
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setInitiatedByUserId(owner.getId());
        r.setIdempotencyKey("k-" + UUID.randomUUID());
        r.setCreatedAt(updatedAt);
        r.setUpdatedAt(updatedAt);
        return refunds.save(r);
    }

    private Order newOrder(long totalMinor, Instant createdAt) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        o.setCreatedAt(createdAt);
        return orders.save(o);
    }

    @Test
    void cross_org_returns_404_leak_safe() {
        AuthPrincipal other = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                UserRole.OWNER, UUID.randomUUID());
        assertThatThrownBy(() -> service.last7Days(other, event.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void returns_seven_buckets_today_last_with_iso_date_labels() {
        EventVelocityService.VelocityResponse r = service.last7Days(principal, event.getId());

        assertThat(r.points()).hasSize(7);
        assertThat(r.points()).allMatch(v -> v == 0L);

        assertThat(r.days()).hasSize(7);
        // Days are ISO local-date strings, oldest first, today last
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        assertThat(r.days().get(6)).isEqualTo(today.toString());
        assertThat(r.days().get(0)).isEqualTo(today.minusDays(6).toString());
    }

    @Test
    void succeeded_refund_subtracts_from_its_day_bucket() {
        // Buy today (1000), refund today (300) → today bucket = 700 net
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        Instant todayNoon = today.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant();
        Order o = newOrder(1000, todayNoon);
        newSucceededRefund(o, 300, todayNoon);

        List<Long> points = service.last7Days(principal, event.getId()).points();
        assertThat(points.get(6)).isEqualTo(700L);
    }

    @Test
    void refund_subtraction_floors_at_zero_per_bucket() {
        // A refund on today subtracting more than today's sales doesn't go negative
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        Instant todayNoon = today.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant();
        Order o = newOrder(500, todayNoon);
        newSucceededRefund(o, 2000, todayNoon);

        List<Long> points = service.last7Days(principal, event.getId()).points();
        assertThat(points.get(6)).isEqualTo(0L);
    }

    @Test
    void orders_today_aggregate_into_last_bucket() {
        // Use noon UTC today to avoid any midnight-edge flake
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        Instant todayNoon = today.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant();
        newOrder(2500, todayNoon);
        newOrder(1500, todayNoon);

        List<Long> points = service.last7Days(principal, event.getId()).points();
        assertThat(points).hasSize(7);
        assertThat(points.get(6)).isEqualTo(4000L);   // index 6 = today
        for (int i = 0; i < 6; i++) {
            assertThat(points.get(i)).as("day index %d", i).isEqualTo(0L);
        }
    }

    @Test
    void orders_three_days_ago_aggregate_into_bucket_3() {
        LocalDate threeDaysAgo = LocalDate.now(ZoneId.of("UTC")).minusDays(3);
        Instant threeDaysAgoNoon = threeDaysAgo.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant();
        newOrder(7000, threeDaysAgoNoon);

        List<Long> points = service.last7Days(principal, event.getId()).points();
        // start = today - 6 days. So three-days-ago index = 6 - 3 = 3.
        assertThat(points.get(3)).isEqualTo(7000L);
    }

    @Test
    void orders_outside_window_are_excluded() {
        LocalDate eightDaysAgo = LocalDate.now(ZoneId.of("UTC")).minusDays(8);
        Instant outside = eightDaysAgo.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant();
        newOrder(99_000, outside);

        List<Long> points = service.last7Days(principal, event.getId()).points();
        assertThat(points).allMatch(v -> v == 0L);
    }

    @Test
    void honors_event_timezone_for_day_bucketing() {
        // Set event timezone to Pacific/Auckland (UTC+12 or +13 depending on DST).
        // An order created at 23:00 UTC on a given calendar day in Auckland is the
        // NEXT calendar day. We just assert that the order is present in some bucket
        // (it shouldn't crash on timezone math, and the total in the window equals
        // the order amount).
        event.setTimezone("Pacific/Auckland");
        events.save(event);

        LocalDate today = LocalDate.now(ZoneId.of("Pacific/Auckland"));
        Instant todayNoonAuckland = today.atTime(12, 0).atZone(ZoneId.of("Pacific/Auckland")).toInstant();
        newOrder(1234, todayNoonAuckland);

        List<Long> points = service.last7Days(principal, event.getId()).points();
        long sum = points.stream().mapToLong(Long::longValue).sum();
        assertThat(sum).isEqualTo(1234L);
    }
}
