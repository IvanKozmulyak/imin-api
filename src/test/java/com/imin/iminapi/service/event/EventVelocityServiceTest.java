package com.imin.iminapi.service.event;

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
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IminIntegrationTest
class EventVelocityServiceTest {

    @Autowired EventVelocityService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    private Organization org;
    private User owner;
    private Event event;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
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
    void tearDown() {
        if (org != null) OrgRows.delete(jdbc, List.of(org.getId()));
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
        assertThatThrownBy(() -> service.windowEndingToday(other, event.getId(), EventVelocityService.DEFAULT_WINDOW_DAYS))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void returns_seven_buckets_today_last_with_iso_date_labels() {
        EventVelocityService.VelocityResponse r = service.windowEndingToday(principal, event.getId(), EventVelocityService.DEFAULT_WINDOW_DAYS);

        assertThat(r.points()).hasSize(7);
        assertThat(r.points()).allMatch(v -> v == 0L);

        assertThat(r.days()).hasSize(7);
        // Days are ISO local-date strings, oldest first, today last
        LocalDate today = LocalDate.now(clock);
        assertThat(r.days().get(6)).isEqualTo(today.toString());
        assertThat(r.days().get(0)).isEqualTo(today.minusDays(6).toString());
    }

    @Test
    void succeeded_refund_subtracts_from_its_day_bucket() {
        // Buy today (1000), refund today (300) → today bucket = 700 net
        LocalDate today = LocalDate.now(clock);
        Instant todayNoon = today.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant();
        Order o = newOrder(1000, todayNoon);
        newSucceededRefund(o, 300, todayNoon);

        List<Long> points = service.windowEndingToday(principal, event.getId(), EventVelocityService.DEFAULT_WINDOW_DAYS).points();
        assertThat(points.get(6)).isEqualTo(700L);
    }

    @ParameterizedTest(name = "requested {0} → {1} buckets")
    @CsvSource({
            "30, 30",
            // clamped to the max
            EventVelocityService.MAX_WINDOW_DAYS + 100 + ", " + EventVelocityService.MAX_WINDOW_DAYS,
            // zero or negative is clamped to one
            "0, 1"
    })
    void window_is_clamped_to_one_through_max_days(int requested, int buckets) {
        EventVelocityService.VelocityResponse r = service.windowEndingToday(principal, event.getId(), requested);
        assertThat(r.points()).hasSize(buckets);
        assertThat(r.days()).hasSize(buckets);

        LocalDate today = LocalDate.now(clock);
        assertThat(r.days().get(buckets - 1)).isEqualTo(today.toString());
        assertThat(r.days().get(0)).isEqualTo(today.minusDays(buckets - 1).toString());
    }

    @Test
    void refund_subtraction_floors_at_zero_per_bucket() {
        // A refund on today subtracting more than today's sales doesn't go negative
        LocalDate today = LocalDate.now(clock);
        Instant todayNoon = today.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant();
        Order o = newOrder(500, todayNoon);
        newSucceededRefund(o, 2000, todayNoon);

        List<Long> points = service.windowEndingToday(principal, event.getId(), EventVelocityService.DEFAULT_WINDOW_DAYS).points();
        assertThat(points.get(6)).isEqualTo(0L);
    }

    @ParameterizedTest(name = "{0} day(s) ago → bucket {1}")
    @CsvSource({
            // index 6 = today; two orders sum
            "0, 6, '2500 1500'",
            // start = today - 6 days, so three days ago is index 6 - 3 = 3
            "3, 3, '7000'"
    })
    void orders_aggregate_into_their_day_bucket(int daysAgo, int bucket, String amounts) {
        // Noon UTC avoids any midnight-edge flake.
        Instant noon = LocalDate.now(clock).minusDays(daysAgo).atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant();
        long sum = 0;
        for (String amount : amounts.split(" ")) {
            newOrder(Long.parseLong(amount), noon);
            sum += Long.parseLong(amount);
        }

        List<Long> points = service.windowEndingToday(principal, event.getId(), EventVelocityService.DEFAULT_WINDOW_DAYS).points();
        assertThat(points).hasSize(7);
        for (int i = 0; i < 7; i++) {
            assertThat(points.get(i)).as("day index %d", i).isEqualTo(i == bucket ? sum : 0L);
        }
    }

    @Test
    void orders_outside_window_are_excluded() {
        LocalDate eightDaysAgo = LocalDate.now(clock).minusDays(8);
        Instant outside = eightDaysAgo.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant();
        newOrder(99_000, outside);

        List<Long> points = service.windowEndingToday(principal, event.getId(), EventVelocityService.DEFAULT_WINDOW_DAYS).points();
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

        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Pacific/Auckland")));
        Instant todayNoonAuckland = today.atTime(12, 0).atZone(ZoneId.of("Pacific/Auckland")).toInstant();
        newOrder(1234, todayNoonAuckland);

        List<Long> points = service.windowEndingToday(principal, event.getId(), EventVelocityService.DEFAULT_WINDOW_DAYS).points();
        long sum = points.stream().mapToLong(Long::longValue).sum();
        assertThat(sum).isEqualTo(1234L);
    }
}
