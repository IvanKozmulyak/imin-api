package com.imin.iminapi.repository;

import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IminIntegrationTest
class FunnelEventRepositoryTest {

    @Autowired FunnelEventRepository funnel;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;

    private Organization org;
    private User owner;

    @BeforeEach
    void setUp() {
        org = fx.org();
        owner = fx.owner(org);
    }

    // event_funnel_events.event_id has an FK to events(id), so the funnel rows must point at a real event.
    private UUID newEvent() {
        return fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(1))).getId();
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
