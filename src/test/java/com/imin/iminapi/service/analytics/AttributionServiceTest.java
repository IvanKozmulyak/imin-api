package com.imin.iminapi.service.analytics;

import com.imin.iminapi.config.TestRateLimitConfig;
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
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class AttributionServiceTest {

    @Autowired AttributionService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired FunnelEventRepository funnel;

    private Organization org;
    private Event event;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        wipe();
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
    void tearDown() { wipe(); }

    private void wipe() {
        funnel.deleteAll(); orders.deleteAll(); events.deleteAll(); users.deleteAll(); orgs.deleteAll();
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
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail(email);
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        orders.save(o);
    }

    @Test
    void attribution_shape_channels_untagged_pct_and_repeat_buyer() {
        // tagged visits: 3 instagram, 1 newsletter; untagged: 1 (no source)
        visit("s1", "instagram", "instagram.com");
        visit("s2", "instagram", "instagram.com");
        visit("s3", "instagram", "instagram.com");
        visit("s4", "newsletter", "mail.google.com");
        visit("s5", null, "blog.example.com");

        // revenue pool = 10000; repeat buyer: a@ has 2 orders, b@ has 1 → 50%
        order("a@example.com", 4000);
        order("a@example.com", 4000);
        order("b@example.com", 2000);

        AttributionResponse r = service.attribution(principal);

        // contract: total pool attributed across tagged channels, rounding-safe
        assertThat(r.attributedRevenueMinor()).isEqualTo(10000L);
        long channelSum = r.channels().stream().mapToLong(AttributionResponse.Channel::revenueMinor).sum();
        assertThat(channelSum).isEqualTo(10000L);

        // 5 total visits, 1 untagged → 20%
        assertThat(r.untaggedPct()).isEqualTo(20);
        // 2 distinct buyers, 1 repeat → 50%
        assertThat(r.repeatBuyerPct()).isEqualTo(50);

        // channels sorted by visits desc: instagram (3) first, newsletter (1)
        assertThat(r.channels()).extracting(AttributionResponse.Channel::source)
                .containsExactly("instagram", "newsletter");
        assertThat(r.channels().get(0).visits()).isEqualTo(3);
        assertThat(r.channels().get(1).visits()).isEqualTo(1);
        // proportional last-touch: 3/4 of 10000 = 7500, remainder 2500
        assertThat(r.channels().get(0).revenueMinor()).isEqualTo(7500L);
        assertThat(r.channels().get(1).revenueMinor()).isEqualTo(2500L);
    }

    @Test
    void attribution_with_no_visits_is_all_zero() {
        AttributionResponse r = service.attribution(principal);
        assertThat(r.attributedRevenueMinor()).isZero();
        assertThat(r.untaggedPct()).isZero();
        assertThat(r.repeatBuyerPct()).isZero();
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
}
