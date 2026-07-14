package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.service.CampaignAttributionService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
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
class CampaignAttributionServiceTest {

    @Autowired CampaignAttributionService attribution;
    @Autowired FunnelEventRepository funnel;
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
}
