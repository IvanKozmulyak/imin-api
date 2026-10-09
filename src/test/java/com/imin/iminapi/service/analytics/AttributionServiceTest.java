package com.imin.iminapi.service.analytics;

import com.imin.iminapi.dto.analytics.AttributionResponse;
import com.imin.iminapi.dto.analytics.UntaggedLinksResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IminIntegrationTest
class AttributionServiceTest {

    @Autowired AttributionService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired FunnelEventRepository funnel;
    @Autowired RefundRepository refunds;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private Event event;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hi@test.example");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Night");
        event.setSlug("night-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setStartsAt(Instant.now().plusSeconds(86_400));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        principal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        if (org != null) OrgRows.delete(jdbc, List.of(org.getId()));
    }

    private void visit(String anon, String source, String referrerHost) {
        FunnelEvent fe = new FunnelEvent();
        fe.setEventId(event.getId());
        fe.setStage(FunnelEvent.STAGE_PAGE_VIEW);
        fe.setAnonId(anon);
        fe.setUtmSource(source);
        fe.setReferrerHost(referrerHost);
        funnel.save(fe);
    }

    private void order(String email, long totalMinor) {
        order(email, totalMinor, null);
    }

    /** V62: orders now carry the landing utm_source, stamped at checkout. */
    private Order order(String email, long totalMinor, String utmSource) {
        return order(email, totalMinor, utmSource, false);
    }

    private Order order(String email, long totalMinor, String utmSource, boolean testMode) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail(email);
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        o.setUtmSource(utmSource);
        o.setTestMode(testMode);
        return orders.save(o);
    }

    private void refund(Order o, long amountMinor, RefundStatus status) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId(o.getStripePaymentIntentId());
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setAmountMinor(amountMinor);
        r.setCurrency("eur");
        r.setReason(RefundReason.OTHER);
        r.setStatus(status);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        refunds.save(r);
    }

    private void beacon(String anon, String stage, String source, String referrerHost) {
        FunnelEvent fe = new FunnelEvent();
        fe.setEventId(event.getId());
        fe.setStage(stage);
        fe.setAnonId(anon);
        fe.setUtmSource(source);
        fe.setReferrerHost(referrerHost);
        funnel.save(fe);
    }

    // ── visitors, not beacon rows ──────────────────────────────────────────

    /** Reloads and a checkout start from one browser are one visitor, per source and in the untagged share. */
    @Test
    void repeated_beacons_from_one_visitor_count_once() {
        beacon("v1", FunnelEvent.STAGE_PAGE_VIEW, "instagram", "instagram.com");
        beacon("v1", FunnelEvent.STAGE_PAGE_VIEW, "instagram", "instagram.com");
        beacon("v1", FunnelEvent.STAGE_CHECKOUT_START, "instagram", "instagram.com");
        beacon("v1", FunnelEvent.STAGE_PAGE_VIEW, null, "blog.example.com");
        beacon("v2", FunnelEvent.STAGE_PAGE_VIEW, "instagram", "instagram.com");

        AttributionResponse r = service.attribution(principal);

        assertThat(r.channels()).extracting(AttributionResponse.Channel::source).containsExactly("instagram");
        assertThat(r.channels().get(0).visits()).isEqualTo(2);
        // v1 had an untagged visit, v2 did not: 1 of 2 visitors.
        assertThat(r.untaggedPct()).isEqualTo(50);
    }

    @Test
    void repeated_beacons_from_one_visitor_count_once_per_slice() {
        visit("v1", "instagram", "instagram.com", "ios");
        visit("v1", "instagram", "instagram.com", "ios");
        visit("v2", "instagram", "instagram.com", "web");
        visit("v2", "instagram", "instagram.com", null);

        assertThat(visitsFor("ios")).isEqualTo(1);
        assertThat(visitsFor("web")).isEqualTo(1);
        assertThat(visitsFor(null)).isEqualTo(2);
    }

    @Test
    void untagged_hosts_count_each_visitor_once() {
        beacon("u1", FunnelEvent.STAGE_PAGE_VIEW, null, "blog.example.com");
        beacon("u1", FunnelEvent.STAGE_PAGE_VIEW, null, "blog.example.com");
        beacon("u1", FunnelEvent.STAGE_CHECKOUT_START, null, "blog.example.com");
        beacon("u2", FunnelEvent.STAGE_PAGE_VIEW, null, "t.co");
        beacon("u3", FunnelEvent.STAGE_PAGE_VIEW, null, "t.co");

        UntaggedLinksResponse r = service.untagged(principal);

        // By rows blog.example.com (3) would rank first; by visitors t.co (2) does.
        assertThat(r.links()).extracting(UntaggedLinksResponse.Link::referrerHost)
                .containsExactly("t.co", "blog.example.com");
        assertThat(r.links()).extracting(UntaggedLinksResponse.Link::visits).containsExactly(2, 1);
    }

    // ── revenue net of refunds, live mode only ─────────────────────────────

    @Test
    void channel_revenue_takes_off_succeeded_refunds_full_and_partial() {
        visit("s1", "instagram", "instagram.com");
        Order full = order("a@example.com", 4000, "instagram");
        refund(full, 4000, RefundStatus.SUCCEEDED);
        Order partial = order("b@example.com", 5000, "instagram");
        refund(partial, 1000, RefundStatus.SUCCEEDED);
        refund(partial, 500, RefundStatus.SUCCEEDED);
        refund(partial, 2000, RefundStatus.PENDING);
        refund(partial, 1000, RefundStatus.FAILED);

        AttributionResponse r = service.attribution(principal);

        assertThat(r.channels().get(0).revenueMinor()).isEqualTo(3500L);
        assertThat(r.attributedRevenueMinor()).isEqualTo(3500L);
    }

    @Test
    void test_mode_orders_and_their_refunds_are_not_revenue() {
        visit("s1", "instagram", "instagram.com");
        order("a@example.com", 2000, "instagram");
        Order test = order("t@example.com", 9000, "instagram", true);
        refund(test, 1000, RefundStatus.SUCCEEDED);
        order("t2@example.com", 7000, "newsletter", true);

        AttributionResponse r = service.attribution(principal);

        assertThat(r.channels()).extracting(AttributionResponse.Channel::source).containsExactly("instagram");
        assertThat(r.channels().get(0).revenueMinor()).isEqualTo(2000L);
        assertThat(r.attributedRevenueMinor()).isEqualTo(2000L);
    }

    /**
     * V62: channel revenue is now a TRUE per-order sum joined on orders.utm_source, not the
     * old tagged-visit-SHARE approximation.
     *
     * <p>The numbers here are deliberately chosen so the two models DISAGREE: by visit share
     * instagram (3 of 4 tagged visits) would have received 7500 of a 10000 pool. The real
     * per-order answer is 8000 — because that is what instagram-tagged buyers actually spent.
     * Visits no longer decide revenue; orders do.
     */
    @Test
    void attribution_shape_channels_and_untagged_pct() {
        // tagged visits: 3 instagram, 1 newsletter; untagged: 1 (no source)
        visit("s1", "instagram", "instagram.com");
        visit("s2", "instagram", "instagram.com");
        visit("s3", "instagram", "instagram.com");
        visit("s4", "newsletter", "mail.google.com");
        visit("s5", null, "blog.example.com");

        order("a@example.com", 4000, "instagram");
        order("a@example.com", 4000, "instagram");
        order("b@example.com", 2000, "newsletter");

        AttributionResponse r = service.attribution(principal);

        assertThat(r.attributedRevenueMinor()).isEqualTo(10000L);
        long channelSum = r.channels().stream().mapToLong(AttributionResponse.Channel::revenueMinor).sum();
        assertThat(channelSum).isEqualTo(10000L);

        // 5 total visits, 1 untagged → 20%
        assertThat(r.untaggedPct()).isEqualTo(20);

        // channels sorted by visits desc: instagram (3) first, newsletter (1)
        assertThat(r.channels()).extracting(AttributionResponse.Channel::source)
                .containsExactly("instagram", "newsletter");
        assertThat(r.channels().get(0).visits()).isEqualTo(3);
        assertThat(r.channels().get(1).visits()).isEqualTo(1);
        // TRUE per-order last-touch — 8000/2000, NOT the visit-share 7500/2500.
        assertThat(r.channels().get(0).revenueMinor()).isEqualTo(8000L);
        assertThat(r.channels().get(1).revenueMinor()).isEqualTo(2000L);
    }

    /**
     * A channel that drove visits but no paid orders now correctly reads 0 revenue. Under the
     * old visit-share model it would have been handed a slice of unrelated revenue.
     */
    @Test
    void channel_with_visits_but_no_orders_reads_zero_revenue() {
        visit("s1", "instagram", "instagram.com");
        visit("s2", "tiktok", "tiktok.com");
        order("a@example.com", 5000, "instagram");

        AttributionResponse r = service.attribution(principal);

        assertThat(r.attributedRevenueMinor()).isEqualTo(5000L);
        assertThat(r.channels()).extracting(AttributionResponse.Channel::source)
                .containsExactlyInAnyOrder("instagram", "tiktok");
        var tiktok = r.channels().stream().filter(c -> c.source().equals("tiktok")).findFirst().orElseThrow();
        assertThat(tiktok.visits()).isEqualTo(1);
        assertThat(tiktok.revenueMinor()).isZero();
    }

    /**
     * Untagged (organic) revenue belongs to NO channel and is not attributed — including every
     * order placed before V62, which carries no utm_source and cannot be back-filled. It is
     * reported as unattributed rather than spread across channels on a guess.
     */
    @Test
    void untagged_order_revenue_is_not_attributed_to_any_channel() {
        visit("s1", "instagram", "instagram.com");
        order("a@example.com", 4000);              // organic / pre-V62 — no utm_source
        order("b@example.com", 1000, "instagram"); // tagged

        AttributionResponse r = service.attribution(principal);

        assertThat(r.attributedRevenueMinor()).isEqualTo(1000L);
        assertThat(r.channels()).hasSize(1);
        assertThat(r.channels().get(0).source()).isEqualTo("instagram");
        assertThat(r.channels().get(0).revenueMinor()).isEqualTo(1000L);
    }

    @Test
    void attribution_with_no_visits_is_all_zero() {
        AttributionResponse r = service.attribution(principal);
        assertThat(r.attributedRevenueMinor()).isZero();
        assertThat(r.untaggedPct()).isZero();
        assertThat(r.channels()).isEmpty();
    }

    @Test
    void untagged_returns_top_hosts_with_suggestions() {
        visit("u1", null, "instagram.com");
        visit("u2", null, "instagram.com");
        visit("u3", null, "blog.example.com");
        // a tagged visit must NOT appear in the untagged list
        visit("t1", "instagram", "instagram.com");

        UntaggedLinksResponse r = service.untagged(principal);

        // instagram.com (2) ranks above blog.example.com (1)
        assertThat(r.links()).extracting(UntaggedLinksResponse.Link::referrerHost)
                .containsExactly("instagram.com", "blog.example.com");
        UntaggedLinksResponse.Link top = r.links().get(0);
        assertThat(top.visits()).isEqualTo(2);
        assertThat(top.sampleUrl()).isNull();
        assertThat(top.suggested().source()).isEqualTo("instagram");
        assertThat(top.suggested().medium()).isEqualTo("social");
        assertThat(top.suggested().campaign()).isEmpty();
        // fallback host
        assertThat(r.links().get(1).suggested().source()).isEqualTo("blog.example.com");
        assertThat(r.links().get(1).suggested().medium()).isEqualTo("referral");
    }

    @Test
    void cross_org_caller_sees_no_data() {
        visit("s1", "instagram", "instagram.com");
        order("a@example.com", 5000);

        AuthPrincipal other = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                UserRole.OWNER, UUID.randomUUID());

        AttributionResponse r = service.attribution(other);
        assertThat(r.attributedRevenueMinor()).isZero();
        assertThat(r.channels()).isEmpty();
        assertThat(r.untaggedPct()).isZero();

        UntaggedLinksResponse u = service.untagged(other);
        assertThat(u.links()).isEmpty();
    }

    // ── client slice (V93) ─────────────────────────────────────────────────

    /**
     * Without a client label, app traffic merges indistinguishably into web
     * "direct" on this live, organizer-facing read-model. These cases pin that
     * the slice actually separates them.
     */
    @Test
    void the_client_filter_separates_app_traffic_from_web() {
        visit("s1", "instagram", "instagram.com", "ios");
        visit("s2", "instagram", "instagram.com", "ios");
        visit("s3", "instagram", "instagram.com", "web");
        visit("s4", "newsletter", "mail.google.com", null);   // pre-V93 row

        assertThat(visitsFor("ios")).isEqualTo(2);
        // NULL counts as web: those rows predate the column, and calling them
        // anything else would invent a distinction that was never made.
        assertThat(visitsFor("web")).isEqualTo(2);
        assertThat(visitsFor("android")).isZero();
        // No filter is every client — exactly the pre-app behaviour.
        assertThat(visitsFor(null)).isEqualTo(4);
    }

    /**
     * An unrecognised label falls back to every client rather than answering an
     * empty chart. An empty chart reads as "nobody came from there", which would
     * be a number nothing backs.
     */
    @Test
    void an_unknown_client_filter_falls_back_to_every_client() {
        visit("s1", "instagram", "instagram.com", "ios");
        visit("s2", "instagram", "instagram.com", "web");

        assertThat(visitsFor("windows-phone")).isEqualTo(2);
    }

    private long visitsFor(String client) {
        return service.attribution(principal, client).channels().stream()
                .mapToLong(AttributionResponse.Channel::visits).sum();
    }

    private void visit(String anon, String source, String referrerHost, String client) {
        FunnelEvent fe = new FunnelEvent();
        fe.setEventId(event.getId());
        fe.setStage(FunnelEvent.STAGE_PAGE_VIEW);
        fe.setAnonId(anon);
        fe.setUtmSource(source);
        fe.setReferrerHost(referrerHost);
        fe.setClient(client);
        funnel.save(fe);
    }
}
