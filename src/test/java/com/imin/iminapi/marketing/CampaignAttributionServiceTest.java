package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.service.CampaignAttributionService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignAttributionServiceTest {

    @Autowired CampaignAttributionService attribution;
    @Autowired FunnelEventRepository funnel;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private Organization org;
    private User owner;

    @BeforeEach
    void setUp() {
        wipe();
        org = new Organization();
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hello@test.example");
        org.setCountry("DE");
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
        funnel.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    // event_funnel_events.event_id has an FK to events(id), so the funnel rows
    // must point at a real persisted event.
    private UUID newEvent() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Test Night");
        e.setSlug("test-night-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.now().plusSeconds(86_400L));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return events.save(e).getId();
    }

    private void beacon(UUID eventId, String stage, String anon, String utmCampaign) {
        FunnelEvent fe = new FunnelEvent();
        fe.setEventId(eventId);
        fe.setStage(stage);
        fe.setAnonId(anon);
        fe.setUtmCampaign(utmCampaign);
        funnel.save(fe);
    }

    @Test
    void countsDistinctCheckoutStartSessionsForTheCampaign() {
        UUID eventId = newEvent();
        UUID campaignId = UUID.randomUUID();
        String tag = campaignId.toString();
        beacon(eventId, FunnelEvent.STAGE_CHECKOUT_START, "anonA", tag);
        beacon(eventId, FunnelEvent.STAGE_CHECKOUT_START, "anonA", tag); // same session, dedup
        beacon(eventId, FunnelEvent.STAGE_CHECKOUT_START, "anonB", tag);
        beacon(eventId, FunnelEvent.STAGE_PAGE_VIEW, "anonC", tag);       // not a conversion signal
        beacon(eventId, FunnelEvent.STAGE_CHECKOUT_START, "anonD", "other-campaign");

        long attributed = attribution.attributedPurchaseCount(campaignId);
        assertThat(attributed).isEqualTo(2); // anonA + anonB
    }

    @Test
    void zeroWhenNoBeaconsCarryTheCampaign() {
        assertThat(attribution.attributedPurchaseCount(UUID.randomUUID())).isEqualTo(0);
    }

    // ---- V62: TRUE per-order attributed revenue (orders.utm_campaign) ----

    /** Persist a paid order for this org carrying (or not carrying) a utm_campaign tag. */
    private void order(UUID eventId, long totalMinor, String utmCampaign) {
        order(eventId, totalMinor, utmCampaign, org.getId());
    }

    private void order(UUID eventId, long totalMinor, String utmCampaign, UUID ownerOrgId) {
        Order o = new Order();
        o.setToken("ord_" + UUID.randomUUID());
        o.setEventId(eventId);
        o.setOrgId(ownerOrgId);
        o.setEmail("buyer-" + UUID.randomUUID() + "@example.com");
        o.setTotalMinor(totalMinor);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setUtmCampaign(utmCampaign);
        orders.save(o);
    }

    @Test
    void sumsRealOrderRevenueForTheMatchingCampaign_andIgnoresNonMatching() {
        UUID eventId = newEvent();
        UUID campaignId = UUID.randomUUID();
        String tag = campaignId.toString();

        order(eventId, 2500, tag);                              // counts
        order(eventId, 1500, tag);                              // counts
        order(eventId, 9900, UUID.randomUUID().toString());     // a DIFFERENT campaign
        order(eventId, 7700, null);                             // organic / untagged

        // Only the two orders carrying this campaign's tag are summed.
        assertThat(attribution.attributedRevenueMinor(org.getId(), campaignId)).isEqualTo(4000);
    }

    @Test
    void revenueIsZeroWhenTheCampaignDroveNoOrders() {
        newEvent();
        // Honest 0 — not a fabricated number, and not null.
        assertThat(attribution.attributedRevenueMinor(org.getId(), UUID.randomUUID())).isZero();
    }

    /**
     * Org scoping (SPINE INVARIANT): another org's order carrying the same campaign tag must
     * never be summed into this org's revenue, even though campaign ids are globally unique.
     */
    @Test
    void revenueNeverCrossesOrgs() {
        UUID eventId = newEvent();
        UUID campaignId = UUID.randomUUID();
        String tag = campaignId.toString();

        Organization other = new Organization();
        other.setName("Other Org");
        other.setSlug("other-org-" + UUID.randomUUID().toString().substring(0, 8));
        other.setContactEmail("other@test.example");
        other.setCountry("DE");
        other = orgs.save(other);

        order(eventId, 2500, tag);                       // ours
        order(eventId, 5000, tag, other.getId());        // theirs — must not count

        assertThat(attribution.attributedRevenueMinor(org.getId(), campaignId)).isEqualTo(2500);
        assertThat(attribution.attributedRevenueMinor(other.getId(), campaignId)).isEqualTo(5000);
    }

    /**
     * The batched form backs the campaign list + the hub's attributedRevMinor tile in ONE
     * query. Campaigns with no attributed revenue map to 0 rather than vanishing.
     */
    @Test
    void batchedRevenue_mapsEveryRequestedCampaign_andIgnoresNonMatching() {
        UUID eventId = newEvent();
        UUID matched = UUID.randomUUID();
        UUID alsoMatched = UUID.randomUUID();
        UUID noRevenue = UUID.randomUUID();
        UUID notAskedFor = UUID.randomUUID();

        order(eventId, 2500, matched.toString());
        order(eventId, 1000, matched.toString());
        order(eventId, 4000, alsoMatched.toString());
        order(eventId, 9999, notAskedFor.toString());   // a campaign outside the window
        order(eventId, 8888, null);                     // organic

        var byCampaign = attribution.attributedRevenueMinorByCampaign(
                org.getId(), List.of(matched, alsoMatched, noRevenue));

        assertThat(byCampaign).containsOnlyKeys(matched, alsoMatched, noRevenue);
        assertThat(byCampaign.get(matched)).isEqualTo(3500);
        assertThat(byCampaign.get(alsoMatched)).isEqualTo(4000);
        assertThat(byCampaign.get(noRevenue)).isZero();
        // The hub tile sums these — untagged + out-of-window revenue is excluded.
        assertThat(byCampaign.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(7500);
    }

    /** Empty input must not attempt an `IN ()` query. */
    @Test
    void batchedRevenue_withNoCampaigns_isEmpty() {
        assertThat(attribution.attributedRevenueMinorByCampaign(org.getId(), List.of())).isEmpty();
    }

    /**
     * utm_campaign is free-form buyer input — a hand-typed link can carry any string.
     * Non-UUID tags must be ignored rather than crashing the read.
     */
    @Test
    void batchedRevenue_ignoresNonUuidTags() {
        UUID eventId = newEvent();
        UUID campaignId = UUID.randomUUID();
        order(eventId, 2500, campaignId.toString());
        order(eventId, 3300, "spring-sale-handwritten");

        var byCampaign = attribution.attributedRevenueMinorByCampaign(org.getId(), List.of(campaignId));
        assertThat(byCampaign).containsOnlyKeys(campaignId);
        assertThat(byCampaign.get(campaignId)).isEqualTo(2500);
    }
}
