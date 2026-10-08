package com.imin.iminapi.repository;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Each test inserts the row its predicate excludes first; dropping that predicate turns it red. */
@IminIntegrationTest
class EventRepositorySoftDeleteDraftTest {

    @Autowired EventRepository events;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private UUID orgA;
    private UUID userA;
    private UUID orgB;
    private UUID userB;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Organization a = fx.org();
        Organization b = fx.org();
        orgIds.addAll(List.of(a.getId(), b.getId()));
        orgA = a.getId();
        userA = fx.owner(a).getId();
        orgB = b.getId();
        userB = fx.owner(b).getId();
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void never_published_draft_of_this_org_is_soft_deleted() {
        UUID id = event(orgA, userA, EventStatus.DRAFT, null, null);

        assertThat(events.softDeleteNeverPublishedDraft(id, orgA, now)).isEqualTo(1);

        assertThat(deletedAt(id)).isNotNull();
        assertThat(events.findActive(id)).isEmpty();
    }

    @Test
    void live_event_is_untouched() {
        UUID live = event(orgA, userA, EventStatus.LIVE, null, null);
        UUID draft = event(orgA, userA, EventStatus.DRAFT, null, null);

        assertThat(events.softDeleteNeverPublishedDraft(live, orgA, now)).isZero();

        assertThat(deletedAt(live)).isNull();
        assertThat(deletedAt(draft)).isNull();
    }

    @Test
    void draft_that_was_published_before_is_untouched() {
        UUID unpublished = event(orgA, userA, EventStatus.DRAFT, now.minusSeconds(3600), null);

        assertThat(events.softDeleteNeverPublishedDraft(unpublished, orgA, now)).isZero();

        assertThat(deletedAt(unpublished)).isNull();
    }

    @Test
    void already_soft_deleted_draft_is_untouched() {
        Instant earlier = now.minusSeconds(3600);
        UUID gone = event(orgA, userA, EventStatus.DRAFT, null, earlier);

        assertThat(events.softDeleteNeverPublishedDraft(gone, orgA, now)).isZero();

        assertThat(deletedAt(gone)).isEqualTo(earlier);
    }

    @Test
    void other_orgs_draft_is_untouched() {
        UUID foreign = event(orgB, userB, EventStatus.DRAFT, null, null);

        assertThat(events.softDeleteNeverPublishedDraft(foreign, orgA, now)).isZero();

        assertThat(deletedAt(foreign)).isNull();
    }

    private Instant deletedAt(UUID id) {
        java.sql.Timestamp ts = jdbc.queryForObject(
                "SELECT deleted_at FROM events WHERE id = ?", java.sql.Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    private UUID event(UUID orgId, UUID userId, EventStatus status, Instant publishedAt, Instant deletedAt) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(userId);
        e.setName("Night");
        e.setSlug("sdd-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setCurrency("EUR");
        e.setPublishedAt(publishedAt);
        e.setDeletedAt(deletedAt);
        return events.save(e).getId();
    }
}
