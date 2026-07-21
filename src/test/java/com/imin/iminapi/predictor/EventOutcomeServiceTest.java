package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.predictor.model.AttendanceSource;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.service.EventOutcomeService;
import com.imin.iminapi.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1 (event outcome record) — publish-freeze snapshot correctness and the
 * finalize job's scans-vs-sales attendance fallback (spec §6.1).
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class EventOutcomeServiceTest {

    @Autowired EventOutcomeService service;
    @Autowired EventOutcomeRepository outcomes;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired TicketTierRepository tiers;
    @Autowired PromoCodeRepository promos;
    @Autowired TicketRepository tickets;
    @Autowired OrderRepository orders;
    @Autowired FunnelEventRepository funnel;
    private final ObjectMapper json = new ObjectMapper();

    private Organization org;
    private User owner;

    private static final Instant PUBLISHED = Instant.parse("2026-02-01T10:00:00Z");
    private static final Instant STARTS = Instant.parse("2026-02-15T20:00:00Z"); // winter
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");

    @BeforeEach
    void setUp() {
        wipe();
        org = new Organization();
        org.setName("Test Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hello@test.example");
        org.setCountry("NL");
        org.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        org = orgs.save(org);

        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        outcomes.deleteAll();
        tickets.deleteAll();
        orders.deleteAll();
        funnel.deleteAll();
        promos.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private Event liveEvent() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Winter Warehouse");
        e.setSlug("winter-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setGenre("House & Techno");
        e.setVenueCity("Amsterdam");
        e.setVenueCountry("NL");
        e.setTimezone("Europe/Amsterdam");
        e.setStartsAt(STARTS);
        e.setEndsAt(STARTS.plus(6, ChronoUnit.HOURS));
        e.setPublishedAt(PUBLISHED);
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return events.save(e);
    }

    private TicketTier tier(UUID eventId, String name, int price, int qty, int sort) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName(name);
        t.setPriceMinor(price);
        t.setQuantity(qty);
        t.setSortOrder(sort);
        t.setSaleStartsAt(PUBLISHED);
        t.setSaleClosesAt(STARTS);
        return tiers.save(t);
    }

    private void ticket(UUID eventId, UUID orderId, UUID tierId, String tierName, int price, String state, boolean redeemed) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(orderId);
        t.setEventId(eventId);
        t.setTierId(tierId);
        t.setTierName(tierName);
        t.setPriceMinor(price);
        t.setState(state);
        if (redeemed) t.setRedeemedAt(Instant.now());
        tickets.save(t);
    }

    private UUID newOrder(UUID eventId) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(eventId);
        o.setOrgId(org.getId());
        o.setEmail("buyer-" + UUID.randomUUID() + "@example.com");
        o.setTotalMinor(1500);
        o.setCurrency("EUR");
        o.setPaymentMethod("free");
        return orders.save(o).getId();
    }

    private void funnelRow(UUID eventId, String stage, String anon) {
        FunnelEvent f = new FunnelEvent();
        f.setEventId(eventId);
        f.setStage(stage);
        f.setAnonId(anon);
        funnel.save(f);
    }

    @Test
    void freeze_snapshotsFrozenFieldsCorrectly() {
        Event e = liveEvent();
        TicketTier a = tier(e.getId(), "Early Bird", 1500, 60, 0);
        tier(e.getId(), "General", 2500, 40, 1);
        PromoCode pc = new PromoCode();
        pc.setEventId(e.getId());
        pc.setCode("WINTER10");
        pc.setDiscountPct(10);
        pc.setMaxUses(50);
        promos.save(pc);

        service.freezeOnPublish(e);

        EventOutcome o = outcomes.findById(e.getId()).orElseThrow();
        assertThat(o.getOrgId()).isEqualTo(org.getId());
        assertThat(o.getCity()).isEqualTo("Amsterdam");
        assertThat(o.getCountry()).isEqualTo("NL");
        assertThat(o.getGenreFamily()).isEqualTo("House & Techno");
        assertThat(o.getCapacity()).isEqualTo(100);
        assertThat(o.getCapacityBand()).isEqualTo(CapacityBand.LE100);
        assertThat(o.getSeason()).isEqualTo(Season.WINTER);
        assertThat(o.getDayOfWeek()).isEqualTo((short) STARTS.atZone(ZONE).getDayOfWeek().getValue());
        assertThat(o.getLeadTimeDays()).isEqualTo(14);
        assertThat(o.getOrganizerTenureDays()).isEqualTo(31);
        assertThat(o.getPriorEventCount()).isEqualTo(0);
        assertThat(o.isSnapshotReconstructed()).isFalse();

        // honesty columns: null, never invented
        assertThat(o.getVenueType()).isNull();
        assertThat(o.getIndoorOpenAir()).isNull();
        assertThat(o.getConceptAiGenerated()).isNull();
        assertThat(o.getPosterAiGenerated()).isNull();
        assertThat(o.getNps()).isNull();
        // not finalized yet
        assertThat(o.getFinalizedAt()).isNull();
        assertThat(o.getSoldTotal()).isNull();

        JsonNode tierJson = readJson(o.getTierStructureJson());
        assertThat(tierJson).hasSize(2);
        assertThat(tierJson.get(0).get("name").asText()).isEqualTo("Early Bird");
        assertThat(tierJson.get(0).get("priceMinor").asInt()).isEqualTo(1500);
        assertThat(tierJson.get(0).get("quantity").asInt()).isEqualTo(60);
        JsonNode promoJson = readJson(o.getPromoConfigJson());
        assertThat(promoJson).hasSize(1);
        assertThat(promoJson.get(0).get("code").asText()).isEqualTo("WINTER10");
        assertThat(promoJson.get(0).get("discountPct").asInt()).isEqualTo(10);

        // a re-freeze is idempotent (same key, one row)
        service.freezeOnPublish(e);
        assertThat(outcomes.findAll()).hasSize(1);

        // tierId sanity (avoids unused-var warning; the id is captured in soldPerTier later)
        assertThat(a.getId()).isNotNull();
    }

    @Test
    void finalize_usesDoorScanAttendance_whenScansExist() {
        Event e = liveEvent();
        TicketTier a = tier(e.getId(), "Early Bird", 1500, 60, 0);
        TicketTier b = tier(e.getId(), "General", 2500, 40, 1);
        service.freezeOnPublish(e);

        // 3 orders drive funnelPaid=3; all tickets hang off the first order.
        UUID o1 = newOrder(e.getId());
        newOrder(e.getId());
        newOrder(e.getId());

        // tier A: 10 sold, 3 redeemed (scans); tier B: 4 sold, 0 redeemed
        for (int i = 0; i < 10; i++) ticket(e.getId(), o1, a.getId(), "Early Bird", 1500, i < 3 ? "redeemed" : "issued", i < 3);
        for (int i = 0; i < 4; i++) ticket(e.getId(), o1, b.getId(), "General", 2500, "issued", false);
        // 2 refunded + 1 revoked (excluded from sold)
        ticket(e.getId(), o1, a.getId(), "Early Bird", 1500, "refunded", false);
        ticket(e.getId(), o1, a.getId(), "Early Bird", 1500, "refunded", false);
        ticket(e.getId(), o1, b.getId(), "General", 2500, "revoked", false);
        funnelRow(e.getId(), FunnelEvent.STAGE_PAGE_VIEW, "s1");
        funnelRow(e.getId(), FunnelEvent.STAGE_PAGE_VIEW, "s1");
        funnelRow(e.getId(), FunnelEvent.STAGE_PAGE_VIEW, "s2");
        funnelRow(e.getId(), FunnelEvent.STAGE_PAGE_VIEW, "s3");
        funnelRow(e.getId(), FunnelEvent.STAGE_PAGE_VIEW, "s4");
        funnelRow(e.getId(), FunnelEvent.STAGE_CHECKOUT_START, "s1");
        funnelRow(e.getId(), FunnelEvent.STAGE_CHECKOUT_START, "s2");

        EventOutcome o = outcomes.findById(e.getId()).orElseThrow();
        Instant now = Instant.now();
        service.finalize(o, e, now);

        EventOutcome fin = outcomes.findById(e.getId()).orElseThrow();
        assertThat(fin.getSoldTotal()).isEqualTo(14);          // 10 + 4 (refunded/revoked excluded)
        assertThat(fin.getGrossRevenueMinor()).isEqualTo(10L * 1500 + 4L * 2500);
        assertThat(fin.getSellOut()).isFalse();                // 14 < 100
        assertThat(fin.getTimeToSellOutHours()).isNull();
        assertThat(fin.getAttendance()).isEqualTo(3);          // door-scan count
        assertThat(fin.getAttendanceSource()).isEqualTo(AttendanceSource.SCANS);
        assertThat(fin.getRefundCount()).isEqualTo(2);
        assertThat(fin.getRefundRate().doubleValue()).isEqualTo(2.0 / 16.0);
        assertThat(fin.getFunnelViews()).isEqualTo(4);         // distinct s1..s4
        assertThat(fin.getFunnelCheckoutStarts()).isEqualTo(2);
        assertThat(fin.getFunnelPaid()).isEqualTo(3);
        assertThat(fin.getCampaignSends()).isEqualTo(0);
        assertThat(fin.getNps()).isNull();
        assertThat(fin.getFinalizedAt()).isNotNull();

        JsonNode perTier = readJson(fin.getSoldPerTierJson());
        assertThat(perTier).hasSize(2);
    }

    @Test
    void finalize_fallsBackToSalesAttendance_whenNoScans() {
        Event e = liveEvent();
        TicketTier a = tier(e.getId(), "GA", 2000, 50, 0);
        service.freezeOnPublish(e);
        UUID ord = newOrder(e.getId());
        for (int i = 0; i < 7; i++) ticket(e.getId(), ord, a.getId(), "GA", 2000, "issued", false);

        EventOutcome o = outcomes.findById(e.getId()).orElseThrow();
        service.finalize(o, e, Instant.now());

        EventOutcome fin = outcomes.findById(e.getId()).orElseThrow();
        assertThat(fin.getSoldTotal()).isEqualTo(7);
        assertThat(fin.getAttendance()).isEqualTo(7);           // tickets-sold fallback
        assertThat(fin.getAttendanceSource()).isEqualTo(AttendanceSource.SALES);
    }

    @Test
    void finalize_marksSellOut_andTimeToSellOut() {
        Event e = liveEvent();
        TicketTier a = tier(e.getId(), "Tiny", 1000, 2, 0);
        service.freezeOnPublish(e);
        UUID ord = newOrder(e.getId());
        ticket(e.getId(), ord, a.getId(), "Tiny", 1000, "issued", false);
        ticket(e.getId(), ord, a.getId(), "Tiny", 1000, "issued", false);

        EventOutcome o = outcomes.findById(e.getId()).orElseThrow();
        service.finalize(o, e, Instant.now());

        EventOutcome fin = outcomes.findById(e.getId()).orElseThrow();
        assertThat(fin.getSellOut()).isTrue();                  // sold 2 >= capacity 2
        assertThat(fin.getTimeToSellOutHours()).isNotNull();
        assertThat(fin.getTimeToSellOutHours()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reconstruct_flagsSnapshotAndSkipsExistingRows() {
        Event e = liveEvent();
        tier(e.getId(), "GA", 2000, 120, 0);

        boolean first = service.reconstructIfAbsent(e);
        assertThat(first).isTrue();
        EventOutcome o = outcomes.findById(e.getId()).orElseThrow();
        assertThat(o.isSnapshotReconstructed()).isTrue();
        assertThat(o.getCapacityBand()).isEqualTo(CapacityBand.B101_300); // 120

        boolean second = service.reconstructIfAbsent(e);
        assertThat(second).isFalse(); // never clobbers an existing row
    }

    private JsonNode readJson(String s) {
        try {
            return json.readTree(s);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
