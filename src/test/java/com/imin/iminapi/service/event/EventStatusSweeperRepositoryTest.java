package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LIVE→PAST tick through the {@link EventStatusSweeper} bean: an ended LIVE event closes, nothing else moves.
 * The sweep is global on the shared database, so only this test's own rows are asserted.
 */
@IminIntegrationTest
class EventStatusSweeperRepositoryTest {

    @Autowired EventStatusSweeper sweeper;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private UUID orgId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        Organization org = new Organization();
        org.setName("Sweeper Test Org");
        org.setSlug("sweeper-org-" + UUID.randomUUID());
        org.setContactEmail("sweeper@example.test");
        org.setCountry("DE");
        org = orgs.save(org);
        orgId = org.getId();

        User owner = new User();
        owner.setEmail("sweeper-owner-" + UUID.randomUUID() + "@example.test");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        userId = users.save(owner).getId();
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, List.of(orgId));
    }

    @Test
    void sweep_closesOnlyTheEndedLiveEvent() {
        // The sweep compares against the app clock, so the fixture times come from it too.
        Instant now = clock.instant();

        Event liveEnded = liveEvent("live-ended");
        liveEnded.setEndsAt(now.minusSeconds(3600));
        UUID liveEndedId = events.save(liveEnded).getId();

        Event liveOngoing = liveEvent("live-ongoing");
        liveOngoing.setEndsAt(now.plusSeconds(3600));
        UUID liveOngoingId = events.save(liveOngoing).getId();

        Event liveNoEnd = liveEvent("live-no-end");
        liveNoEnd.setEndsAt(null);
        UUID liveNoEndId = events.save(liveNoEnd).getId();

        Event draftEnded = new Event();
        draftEnded.setOrgId(orgId);
        draftEnded.setCreatedBy(userId);
        draftEnded.setName("Draft Ended");
        draftEnded.setSlug("draft-ended-" + UUID.randomUUID());
        draftEnded.setVisibility(EventVisibility.PUBLIC);
        draftEnded.setStatus(EventStatus.DRAFT);
        draftEnded.setCurrency("EUR");
        draftEnded.setEndsAt(now.minusSeconds(3600));
        UUID draftEndedId = events.save(draftEnded).getId();

        // A committed sentinel well before the tick, so "updatedAt advanced" cannot pass by coincidence.
        Instant sentinel = now.minusSeconds(86_400);
        jdbc.update("UPDATE events SET updated_at = ? WHERE id = ?", Timestamp.from(sentinel), liveEndedId);
        // One context serves the whole run: release the lock an earlier tick may still hold.
        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = ?", "EventStatusSweeper.sweep");

        sweeper.sweep();

        Event reloadedEnded = events.findById(liveEndedId).orElseThrow();
        assertThat(reloadedEnded.getStatus()).isEqualTo(EventStatus.PAST);
        assertThat(reloadedEnded.getUpdatedAt()).isAfter(sentinel);

        assertThat(events.findById(liveOngoingId).orElseThrow().getStatus()).isEqualTo(EventStatus.LIVE);
        assertThat(events.findById(liveNoEndId).orElseThrow().getStatus()).isEqualTo(EventStatus.LIVE);
        assertThat(events.findById(draftEndedId).orElseThrow().getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    private Event liveEvent(String slugSuffix) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(userId);
        e.setName("Test " + slugSuffix);
        e.setSlug(slugSuffix + "-" + UUID.randomUUID());
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(clock.instant().minusSeconds(7200));
        e.setCurrency("EUR");
        return e;
    }
}
