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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence test for {@link EventRepository#markLivePast(Instant, Instant)}.
 *
 * <p>Seeds four events covering every boundary case and asserts that exactly one
 * transitions to PAST — the LIVE event whose {@code endsAt} is in the past.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EventStatusSweeperRepositoryTest {

    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private UUID orgId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        Organization org = new Organization();
        org.setName("Sweeper Test Org");
        org.setSlug("sweeper-org-" + UUID.randomUUID().toString().substring(0, 8));
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

    @Test
    void markLivePast_transitionsOnlyLiveEndedEvent() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");

        // 1. LIVE event that ended 1 hour ago — should flip to PAST
        Event liveEnded = liveEvent("live-ended");
        liveEnded.setEndsAt(now.minusSeconds(3600));
        UUID liveEndedId = events.save(liveEnded).getId();

        // 2. LIVE event ending in the future — must stay LIVE
        Event liveOngoing = liveEvent("live-ongoing");
        liveOngoing.setEndsAt(now.plusSeconds(3600));
        UUID liveOngoingId = events.save(liveOngoing).getId();

        // 3. LIVE event with null endsAt — must stay LIVE (no end date)
        Event liveNoEnd = liveEvent("live-no-end");
        liveNoEnd.setEndsAt(null);
        UUID liveNoEndId = events.save(liveNoEnd).getId();

        // 4. DRAFT event that has an ended endsAt — must NOT change (not LIVE)
        Event draftEnded = new Event();
        draftEnded.setOrgId(orgId);
        draftEnded.setCreatedBy(userId);
        draftEnded.setName("Draft Ended");
        draftEnded.setSlug("draft-ended-" + UUID.randomUUID().toString().substring(0, 8));
        draftEnded.setVisibility(EventVisibility.PUBLIC);
        draftEnded.setStatus(EventStatus.DRAFT);
        draftEnded.setCurrency("EUR");
        draftEnded.setEndsAt(now.minusSeconds(3600));
        UUID draftEndedId = events.save(draftEnded).getId();

        events.flush();

        // Record updatedAt before the sweep to verify it advances
        Instant updatedAtBefore = events.findById(liveEndedId).orElseThrow().getUpdatedAt();

        // Run the bulk update
        int updated = events.markLivePast(now, now);

        assertThat(updated).isEqualTo(1);

        // The ended LIVE event must now be PAST
        Event reloadedEnded = events.findById(liveEndedId).orElseThrow();
        assertThat(reloadedEnded.getStatus()).isEqualTo(EventStatus.PAST);
        // updatedAt must have advanced (bulk JPQL sets it to :now, bypassing @PreUpdate)
        assertThat(reloadedEnded.getUpdatedAt()).isAfterOrEqualTo(updatedAtBefore);

        // All others must be unchanged
        assertThat(events.findById(liveOngoingId).orElseThrow().getStatus()).isEqualTo(EventStatus.LIVE);
        assertThat(events.findById(liveNoEndId).orElseThrow().getStatus()).isEqualTo(EventStatus.LIVE);
        assertThat(events.findById(draftEndedId).orElseThrow().getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void markLivePast_returnsZero_whenNoEligibleEvents() {
        // Only a LIVE ongoing event — nothing to transition
        Event liveOngoing = liveEvent("live-ongoing-only");
        liveOngoing.setEndsAt(Instant.now().plusSeconds(3600));
        events.save(liveOngoing);
        events.flush();

        int updated = events.markLivePast(Instant.now(), Instant.now());

        assertThat(updated).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Event liveEvent(String slugSuffix) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(userId);
        e.setName("Test " + slugSuffix);
        e.setSlug(slugSuffix + "-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minusSeconds(7200));
        e.setCurrency("EUR");
        return e;
    }
}
