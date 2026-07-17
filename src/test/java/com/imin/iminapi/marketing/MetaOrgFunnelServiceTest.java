package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.MetaFunnelDto;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.service.MetaConnectionService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Org-wide Meta signal-health funnel (spec §8): the 3 real sales-funnel stages,
 * generalized from one event to all of an org's active events over a 30-day
 * window and mapped onto Meta's event vocabulary.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class MetaOrgFunnelServiceTest {

    @Autowired MetaConnectionService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired FunnelEventRepository funnel;
    @Autowired MetaCapiEventRepository capiEvents;

    private Organization org;
    private User owner;

    @BeforeEach
    void setUp() {
        wipe();
        org = newOrg();
        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        capiEvents.deleteAll();
        funnel.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    // ---- fixtures ---------------------------------------------------------

    private Organization newOrg() {
        Organization o = new Organization();
        o.setName("Org");
        o.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("hi@test.example");
        o.setCountry("DE");
        return orgs.save(o);
    }

    private UUID newEvent(UUID orgId) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Night");
        e.setSlug("night-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.now().plusSeconds(86_400));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return events.save(e).getId();
    }

    private void beacon(UUID eventId, String stage, String anon, Instant createdAt) {
        FunnelEvent fe = new FunnelEvent();
        fe.setEventId(eventId);
        fe.setStage(stage);
        fe.setAnonId(anon);
        fe.setCreatedAt(createdAt);
        funnel.save(fe);
    }

    private void beacon(UUID eventId, String stage, String anon) {
        beacon(eventId, stage, anon, Instant.now());
    }

    private void order(UUID orgId, UUID eventId, Instant createdAt) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setEmail("buyer-" + UUID.randomUUID() + "@example.com");
        o.setTotalMinor(1000);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        o.setCreatedAt(createdAt);
        orders.save(o);
    }

    private void capiEvent(UUID orgId, String status, Instant createdAt) {
        MetaCapiEvent e = new MetaCapiEvent();
        e.setId(UUID.randomUUID());
        e.setOrgId(orgId);
        e.setOrderId(UUID.randomUUID()); // no FK to orders; UNIQUE(order_id) only
        e.setOrderToken(UUID.randomUUID().toString().replace("-", ""));
        e.setPixelId("123456");
        e.setEventName("Purchase");
        e.setEmailSha256("a".repeat(64));
        e.setValueMinor(1000);
        e.setCurrency("eur");
        e.setEventTime(createdAt.getEpochSecond());
        e.setStatus(status);
        e.setCreatedAt(createdAt);
        if (MetaCapiEvent.STATUS_SENT.equals(status)) e.setSentAt(createdAt);
        capiEvents.save(e);
    }

    private Map<String, MetaFunnelDto.Stage> byMetaEvent(MetaFunnelDto dto) {
        return dto.stages().stream()
                .collect(Collectors.toMap(MetaFunnelDto.Stage::metaEvent, Function.identity()));
    }

    // ---- tests ------------------------------------------------------------

    @Test
    void maps_three_stages_and_aggregates_across_all_org_events() {
        UUID e1 = newEvent(org.getId());
        UUID e2 = newEvent(org.getId());

        // PAGE_VIEW: 3 distinct sessions across the two events (e1: s1,s2 — e2: s3)
        beacon(e1, FunnelEvent.STAGE_PAGE_VIEW, "s1");
        beacon(e1, FunnelEvent.STAGE_PAGE_VIEW, "s2");
        beacon(e2, FunnelEvent.STAGE_PAGE_VIEW, "s3");
        // CHECKOUT_START: 2 distinct sessions (e1: s1 — e2: s3)
        beacon(e1, FunnelEvent.STAGE_CHECKOUT_START, "s1");
        beacon(e2, FunnelEvent.STAGE_CHECKOUT_START, "s3");
        // PAYMENTS_COMPLETED: 3 orders across both events
        order(org.getId(), e1, Instant.now());
        order(org.getId(), e1, Instant.now());
        order(org.getId(), e2, Instant.now());

        MetaFunnelDto dto = service.funnel(org.getId());

        assertThat(dto.windowDays()).isEqualTo(30);
        assertThat(dto.stages()).hasSize(3);

        // order + names of the mapping are exactly as specified
        assertThat(dto.stages()).extracting(MetaFunnelDto.Stage::metaEvent)
                .containsExactly("PageView", "InitiateCheckout", "Purchase");
        assertThat(dto.stages()).extracting(MetaFunnelDto.Stage::iminStage)
                .containsExactly("PAGE_VIEW", "CHECKOUT_START", "PAYMENTS_COMPLETED");

        var byStage = byMetaEvent(dto);
        assertThat(byStage.get("PageView").iminCount()).isEqualTo(3L);
        assertThat(byStage.get("InitiateCheckout").iminCount()).isEqualTo(2L);
        assertThat(byStage.get("Purchase").iminCount()).isEqualTo(3L);
    }

    @Test
    void distinct_sessions_counted_per_stage() {
        UUID e1 = newEvent(org.getId());
        // s1 views twice, s2 once → 2 distinct
        beacon(e1, FunnelEvent.STAGE_PAGE_VIEW, "s1");
        beacon(e1, FunnelEvent.STAGE_PAGE_VIEW, "s1");
        beacon(e1, FunnelEvent.STAGE_PAGE_VIEW, "s2");

        MetaFunnelDto dto = service.funnel(org.getId());
        assertThat(byMetaEvent(dto).get("PageView").iminCount()).isEqualTo(2L);
    }

    @Test
    void excludes_other_orgs_events() {
        UUID mine = newEvent(org.getId());
        beacon(mine, FunnelEvent.STAGE_PAGE_VIEW, "s1");
        order(org.getId(), mine, Instant.now());

        // a whole other org with its own event, beacons and orders
        Organization other = newOrg();
        UUID theirs = newEvent(other.getId());
        beacon(theirs, FunnelEvent.STAGE_PAGE_VIEW, "x1");
        beacon(theirs, FunnelEvent.STAGE_PAGE_VIEW, "x2");
        beacon(theirs, FunnelEvent.STAGE_CHECKOUT_START, "x1");
        order(other.getId(), theirs, Instant.now());
        order(other.getId(), theirs, Instant.now());

        MetaFunnelDto dto = service.funnel(org.getId());
        var byStage = byMetaEvent(dto);
        assertThat(byStage.get("PageView").iminCount()).isEqualTo(1L);
        assertThat(byStage.get("InitiateCheckout").iminCount()).isZero();
        assertThat(byStage.get("Purchase").iminCount()).isEqualTo(1L);
    }

    @Test
    void empty_org_returns_zeros_not_null_or_error() {
        MetaFunnelDto dto = service.funnel(org.getId());

        assertThat(dto.windowDays()).isEqualTo(30);
        assertThat(dto.stages()).hasSize(3);
        for (MetaFunnelDto.Stage s : dto.stages()) {
            assertThat(s.iminCount()).isZero();
        }
        var byStage = byMetaEvent(dto);
        // imin-side is always a real number (zero here) — never null
        assertThat(byStage.get("PageView").iminCount()).isZero();
        // Meta-side: null for the two browser-only stages, real 0 for Purchase
        assertThat(byStage.get("PageView").metaReceivedCount()).isNull();
        assertThat(byStage.get("InitiateCheckout").metaReceivedCount()).isNull();
        assertThat(byStage.get("Purchase").metaReceivedCount()).isEqualTo(0L);
    }

    @Test
    void window_excludes_beacons_and_orders_older_than_30_days() {
        UUID e1 = newEvent(org.getId());
        Instant old = Instant.now().minus(31, ChronoUnit.DAYS);
        Instant fresh = Instant.now().minus(1, ChronoUnit.DAYS);

        beacon(e1, FunnelEvent.STAGE_PAGE_VIEW, "fresh", fresh);
        beacon(e1, FunnelEvent.STAGE_PAGE_VIEW, "stale", old);
        order(org.getId(), e1, fresh);
        order(org.getId(), e1, old);

        MetaFunnelDto dto = service.funnel(org.getId());
        var byStage = byMetaEvent(dto);
        assertThat(byStage.get("PageView").iminCount()).isEqualTo(1L);   // stale excluded
        assertThat(byStage.get("Purchase").iminCount()).isEqualTo(1L);   // stale excluded
    }

    @Test
    void meta_received_count_reflects_only_sent_purchase_events_in_window() {
        // 2 delivered, 1 still pending, 1 dead, 1 sent-but-stale → only 2 count
        capiEvent(org.getId(), MetaCapiEvent.STATUS_SENT, Instant.now());
        capiEvent(org.getId(), MetaCapiEvent.STATUS_SENT, Instant.now());
        capiEvent(org.getId(), MetaCapiEvent.STATUS_PENDING, Instant.now());
        capiEvent(org.getId(), MetaCapiEvent.STATUS_DEAD, Instant.now());
        capiEvent(org.getId(), MetaCapiEvent.STATUS_SENT, Instant.now().minus(31, ChronoUnit.DAYS));
        // another org's delivered event must not leak in
        Organization other = newOrg();
        capiEvent(other.getId(), MetaCapiEvent.STATUS_SENT, Instant.now());

        MetaFunnelDto dto = service.funnel(org.getId());
        var byStage = byMetaEvent(dto);
        assertThat(byStage.get("Purchase").metaReceivedCount()).isEqualTo(2L);
        // upper stages never carry a Meta-side number
        assertThat(byStage.get("PageView").metaReceivedCount()).isNull();
        assertThat(byStage.get("InitiateCheckout").metaReceivedCount()).isNull();
    }

    @Test
    void cross_org_caller_sees_all_zeros() {
        UUID e1 = newEvent(org.getId());
        beacon(e1, FunnelEvent.STAGE_PAGE_VIEW, "s1");
        order(org.getId(), e1, Instant.now());
        capiEvent(org.getId(), MetaCapiEvent.STATUS_SENT, Instant.now());

        MetaFunnelDto dto = service.funnel(UUID.randomUUID()); // unrelated org
        var byStage = byMetaEvent(dto);
        assertThat(byStage.get("PageView").iminCount()).isZero();
        assertThat(byStage.get("InitiateCheckout").iminCount()).isZero();
        assertThat(byStage.get("Purchase").iminCount()).isZero();
        assertThat(byStage.get("Purchase").metaReceivedCount()).isEqualTo(0L);
    }
}
