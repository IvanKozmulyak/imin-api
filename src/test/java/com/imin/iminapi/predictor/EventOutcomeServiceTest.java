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
import com.imin.iminapi.predictor.service.PredictorSegmentKeys;
import com.imin.iminapi.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
        // MERGE keys, not display spellings (predictor-edge-3) — these two columns are matched
        // by equality in the corpus segment queries.
        assertThat(o.getCity()).isEqualTo("amsterdam");
        assertThat(o.getCountry()).isEqualTo("NL");
        assertThat(o.getGenreFamily()).isEqualTo("house & techno");
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
        // No beacon row exists for this event at all: that is an ABSENCE of funnel data, not a
        // measurement of zero page views. The corpus must not learn a number nobody observed.
        assertThat(fin.getFunnelViews()).isNull();
        assertThat(fin.getFunnelCheckoutStarts()).isNull();
    }

    @Test
    void finalize_leavesRefundRateNull_whenNothingWasIssued() {
        Event e = liveEvent();
        tier(e.getId(), "GA", 2000, 50, 0);
        service.freezeOnPublish(e);

        EventOutcome o = outcomes.findById(e.getId()).orElseThrow();
        service.finalize(o, e, Instant.now());

        EventOutcome fin = outcomes.findById(e.getId()).orElseThrow();
        assertThat(fin.getSoldTotal()).isZero();
        assertThat(fin.getRefundCount()).isZero();
        assertThat(fin.getRefundRate()).isNull();   // 0 of 0 issued is undefined, not a 0.0000 rate
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

    /**
     * predictor-edge-3: the segment columns are matched by equality, so freezing the DISPLAY
     * spelling split one cluster across "Techno"/"techno" and "Amsterdam"/"AMSTERDAM" — which
     * shrinks clusterSize, and clusterSize is what picks the §5 language tier and what the ≥5
     * privacy floor tests. Case variants must land in ONE segment.
     */
    @Test
    void freeze_mergesCaseVariantsIntoOneSegment() {
        Event lower = liveEvent();
        lower.setGenre("techno");
        lower.setVenueCity("Amsterdam");
        events.save(lower);
        tier(lower.getId(), "GA", 2000, 120, 0);

        Event upper = liveEvent();
        upper.setGenre(" TECHNO ");
        upper.setVenueCity("AMSTERDAM");
        events.save(upper);
        tier(upper.getId(), "GA", 2000, 120, 0);

        service.freezeOnPublish(lower);
        service.freezeOnPublish(upper);

        EventOutcome a = outcomes.findById(lower.getId()).orElseThrow();
        EventOutcome b = outcomes.findById(upper.getId()).orElseThrow();
        assertThat(a.getGenreFamily()).isEqualTo("techno").isEqualTo(b.getGenreFamily());
        assertThat(a.getCity()).isEqualTo("amsterdam").isEqualTo(b.getCity());

        // …and one segment query returns both once they are finalized.
        service.finalize(a, lower, Instant.now());
        service.finalize(b, upper, Instant.now());
        assertThat(outcomes.findFinalizedByCitySegment("amsterdam", "techno",
                CapacityBand.B101_300, Season.WINTER))
                .extracting(EventOutcome::getEventId)
                .containsExactlyInAnyOrder(lower.getId(), upper.getId());
    }

    /**
     * predictor-edge-5: the freeze runs inside {@code EventService.publish}'s transaction, so a
     * genre too long for {@code event_outcomes.genre_family} (VARCHAR(64)) would fail that INSERT
     * and roll the WHOLE publish back. Correction to the finding's premise: the source column is
     * {@code events.genre} VARCHAR(64) (V6 — the VARCHAR(255) genre is on {@code generated_event},
     * a different table), so the events row cannot hold a longer value today and the freeze
     * cannot be handed one; the clamp is belt and braces for the day that column is widened, and
     * it keeps two distinct long values in two distinct segments. The reachable half of this
     * finding is bounding the genre AT THE EDGE — see
     * {@code EventControllerTest.patch_rejects_a_genre_longer_than_the_outcome_column}, which
     * without the rule answered an unnamed "Request violates a data constraint" from the events
     * UPDATE instead of naming the field.
     */
    @Test
    void segmentGenreKeyIsClampedToTheOutcomeColumnWidth() {
        String one = "z".repeat(80) + "-one";
        String two = "z".repeat(80) + "-two";

        assertThat(PredictorSegmentKeys.genreKey(one)).hasSize(64);
        assertThat(PredictorSegmentKeys.genreKey(two)).hasSize(64);
        assertThat(PredictorSegmentKeys.genreKey(one)).isNotEqualTo(PredictorSegmentKeys.genreKey(two));
        assertThat(PredictorSegmentKeys.genreKey("House & Techno")).isEqualTo("house & techno");
    }

    /**
     * predictor-edge-10: a cancelled or soft-deleted event must never become a comparable.
     * Its tickets are refunded, so finalizing it stamps sold≈0 / sell_out=false / attendance≈0
     * — and {@code finalizedAt is not null} is the ONLY membership test the three cross-org
     * corpus segment queries apply, so the row would drag every other organizer's segment
     * aggregates and pacing shapes down with a result that never happened.
     */
    @Test
    void dueForFinalize_excludesSoftDeletedAndCancelledEvents() {
        Instant cutoff = STARTS.plus(1, ChronoUnit.DAYS);

        Event due = liveEvent();
        service.freezeOnPublish(due);

        Event deleted = liveEvent();
        service.freezeOnPublish(deleted);
        deleted.setDeletedAt(Instant.parse("2026-02-20T00:00:00Z"));
        events.save(deleted);

        Event cancelled = liveEvent();
        service.freezeOnPublish(cancelled);
        cancelled.setStatus(EventStatus.CANCELLED);
        events.save(cancelled);

        List<EventOutcome> page = outcomes.findDueForFinalize(cutoff, PageRequest.of(0, 50));

        assertThat(page).extracting(EventOutcome::getEventId).containsExactly(due.getId());
    }

    /**
     * predictor-edge-4: an event with no ticket tiers publishes (EventValidator requires none),
     * and the tier-sum query COALESCEs to 0 — which used to freeze capacity = 0 / band = LE100
     * and finalize sell_out = false. Zero capacity is UNKNOWN capacity: the row must not join
     * the ≤100 segment of every other organizer's corpus, and "did not sell out" is not a fact
     * anyone stated.
     */
    @Test
    void freeze_leavesCapacityAndBandNull_whenTheEventHasNoTiers() {
        Event e = liveEvent();   // no tier() calls

        service.freezeOnPublish(e);

        EventOutcome o = outcomes.findById(e.getId()).orElseThrow();
        assertThat(o.getCapacity()).isNull();
        assertThat(o.getCapacityBand()).isNull();
        assertThat(CapacityBand.of(0)).isNull();

        service.finalize(o, e, Instant.now());

        EventOutcome fin = outcomes.findById(e.getId()).orElseThrow();
        assertThat(fin.getSoldTotal()).isZero();          // measured: no tickets exist
        assertThat(fin.getSellOut()).isNull();            // undefined, not "did not sell out"
        assertThat(fin.getTimeToSellOutHours()).isNull();
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
