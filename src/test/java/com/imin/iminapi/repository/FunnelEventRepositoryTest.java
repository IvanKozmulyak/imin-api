package com.imin.iminapi.repository;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class FunnelEventRepositoryTest {

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

    private void insert(UUID eventId, String stage, String anonId) {
        FunnelEvent e = new FunnelEvent();
        e.setEventId(eventId);
        e.setStage(stage);
        e.setAnonId(anonId);
        funnel.save(e);
    }

    @Test
    void counts_distinct_sessions_per_stage() {
        UUID eventId = newEvent();
        UUID other = newEvent();
        // PAGE_VIEW: 2 distinct sessions (s1 twice + s2)
        insert(eventId, FunnelEvent.STAGE_PAGE_VIEW, "s1");
        insert(eventId, FunnelEvent.STAGE_PAGE_VIEW, "s1");
        insert(eventId, FunnelEvent.STAGE_PAGE_VIEW, "s2");
        // CHECKOUT_START: 1 distinct session
        insert(eventId, FunnelEvent.STAGE_CHECKOUT_START, "s1");
        // a row for a different event must not leak in
        insert(other, FunnelEvent.STAGE_PAGE_VIEW, "s9");

        Map<String, Long> byStage = new HashMap<>();
        for (Object[] row : funnel.countDistinctAnonByStage(eventId)) {
            byStage.put((String) row[0], (Long) row[1]);
        }

        assertThat(byStage.get(FunnelEvent.STAGE_PAGE_VIEW)).isEqualTo(2L);
        assertThat(byStage.get(FunnelEvent.STAGE_CHECKOUT_START)).isEqualTo(1L);
    }
}
