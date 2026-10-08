package com.imin.iminapi.audienceplan.service;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanPruner's delete on the shared Postgres.
 * Plans are dated around 2001 with the clock in 2001 too, so rows other tests leave behind (dated now) are never
 * older than the cutoff and these rows are always the oldest in a batch.
 */
@IminIntegrationTest
class PlanPrunerIntegrationTest {

    static final Instant NOW = Instant.parse("2001-06-01T09:00:00Z");
    static final Instant OLD = NOW.minus(Duration.ofDays(60));
    static final Instant YOUNG = NOW.minus(Duration.ofDays(10));

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;

    private UUID orgId;
    private UUID userId;
    private PlanPruner pruner;

    @BeforeEach
    void setUp() {
        Organization o = new Organization();
        o.setName("Prune Org");
        o.setSlug("prune-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("prune@example.com");
        o.setCountry("FR");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        u.setEmail("prune-" + UUID.randomUUID() + "@example.com");
        userId = users.save(u).getId();
        pruner = new PlanPruner(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from audience_experiments where org_id = ?", orgId);
        jdbc.update("update audience_plans set superseded_by = null where org_id = ?", orgId);
        jdbc.update("delete from audience_plans where org_id = ?", orgId);
        jdbc.update("delete from events where org_id = ?", orgId);
        jdbc.update("delete from users where org_id = ?", orgId);
        jdbc.update("delete from organizations where id = ?", orgId);
    }

    @Test
    void oldSupersededChain_isDeleted_theCurrentPlanStaysCurrent() {
        UUID e = event();
        List<UUID> chain = chain(e, OLD, OLD.plusSeconds(60), OLD.plusSeconds(120));
        UUID prunedSegment = planSegment(chain.get(0));
        UUID keptSegment = planSegment(chain.get(2));

        assertThat(pruner.prune()).isEqualTo(2);

        assertThat(plans(e)).containsExactly(chain.get(2));
        assertThat(supersededBy(chain.get(2))).isNull();
        assertThat(jdbc.queryForObject("select count(*) from audience_plan_segments where id = ?", Integer.class,
                prunedSegment)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audience_plan_segments where id = ?", Integer.class,
                keptSegment)).isEqualTo(1);
    }

    @Test
    void supersededYoungerThan30Days_isKept() {
        UUID e = event();
        List<UUID> chain = chain(e, YOUNG, YOUNG.plusSeconds(60));

        pruner.prune();

        assertThat(plans(e)).containsExactlyElementsOf(chain);
    }

    @Test
    void anOldCurrentPlan_isNeverDeleted() {
        UUID e = event();
        List<UUID> chain = chain(e, OLD);

        pruner.prune();

        assertThat(plans(e)).containsExactlyElementsOf(chain);
    }

    @Test
    void aPlanAnExperimentPointsTo_andEveryLaterPlan_areKept_earlierOnesGo() {
        UUID e = event();
        List<UUID> chain = chain(e, OLD, OLD.plusSeconds(60), OLD.plusSeconds(120), OLD.plusSeconds(180));
        experiment(e, chain.get(1), null);

        pruner.prune();

        assertThat(plans(e)).containsExactly(chain.get(1), chain.get(2), chain.get(3));
        assertThat(supersededBy(chain.get(1))).isEqualTo(chain.get(2));
        assertThat(supersededBy(chain.get(2))).isEqualTo(chain.get(3));
    }

    @Test
    void anExperimentOnAPlanSegmentOnly_protectsThatPlanToo() {
        UUID e = event();
        List<UUID> chain = chain(e, OLD, OLD.plusSeconds(60), OLD.plusSeconds(120));
        experiment(e, null, planSegment(chain.get(1)));

        pruner.prune();

        assertThat(plans(e)).containsExactly(chain.get(1), chain.get(2));
        assertThat(supersededBy(chain.get(1))).isEqualTo(chain.get(2));
    }

    @Test
    void anExperimentOnAnotherEvent_doesNotProtectThisEventsPlans() {
        UUID invited = event();
        List<UUID> invitedChain = chain(invited, OLD, OLD.plusSeconds(60));
        experiment(invited, invitedChain.get(0), null);
        UUID other = event();
        List<UUID> otherChain = chain(other, OLD, OLD.plusSeconds(60));

        pruner.prune();

        assertThat(plans(invited)).containsExactlyElementsOf(invitedChain);
        assertThat(plans(other)).containsExactly(otherChain.get(1));
    }

    @Test
    void batches_stopAtTheCap_andAPartialRunLeavesTheRestOfTheChainLinked() {
        UUID e = event();
        List<UUID> chain = chain(e, OLD, OLD.plusSeconds(1), OLD.plusSeconds(2), OLD.plusSeconds(3),
                OLD.plusSeconds(4), OLD.plusSeconds(5), OLD.plusSeconds(6));
        PlanPruner small = new PlanPruner(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC), 2, 2);

        assertThat(small.prune()).isEqualTo(4);
        assertThat(plans(e)).containsExactly(chain.get(4), chain.get(5), chain.get(6));
        assertThat(supersededBy(chain.get(4))).isEqualTo(chain.get(5));

        // The next run takes the remaining 2; the empty batch after them ends it.
        assertThat(small.prune()).isEqualTo(2);
        assertThat(plans(e)).containsExactly(chain.get(6));
        assertThat(supersededBy(chain.get(6))).isNull();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private UUID event() {
        Event ev = new Event();
        ev.setOrgId(orgId);
        ev.setName("Prune Night");
        ev.setSlug("prune-event-" + UUID.randomUUID().toString().substring(0, 8));
        ev.setVisibility(EventVisibility.PUBLIC);
        ev.setStatus(EventStatus.LIVE);
        ev.setCreatedBy(userId);
        ev.setCurrency("EUR");
        return events.save(ev).getId();
    }

    /** Plans of one event created at {@code times}, each superseded by the next; the last is current. */
    private List<UUID> chain(UUID eventId, Instant... times) {
        List<UUID> ids = new ArrayList<>();
        for (Instant t : times) ids.add(plan(eventId, t));
        for (int i = 0; i + 1 < ids.size(); i++) {
            jdbc.update("update audience_plans set superseded_by = ? where id = ?", ids.get(i + 1), ids.get(i));
        }
        return ids;
    }

    private UUID plan(UUID eventId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audience_plans (id, org_id, event_id, mode, capacity, target_pct, target_tickets,
                  tickets_per_order, excluded_segments, mailable, verdict, gap_low, gap_high, reach_needed,
                  small_groups_not_shown, other_genre_invited, other_genre_held_back, exclusions, today_date,
                  event_date, launch_date, event_started, actions, logic_version, priors_version,
                  calibration_version, inputs_hash, created_at)
                VALUES (?, ?, ?, 'warm', 300, 85, 255, 1.6, '[]', 10, 'weak', 1, 2, '{}', 0, FALSE, 0, '{}',
                  DATE '2001-01-01', DATE '2001-12-01', DATE '2001-01-01', FALSE, '[]', 1, 1, 0, 'h', ?)""",
                id, orgId, eventId, Timestamp.from(createdAt));
        return id;
    }

    private UUID planSegment(UUID planId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audience_plan_segments (id, plan_id, position, class, genre_fit, mailable, rate_low,
                  rate_mid, rate_high, tickets_per_order, expected_low, expected_mid, expected_high, confidence, reason)
                VALUES (?, ?, 0, 'loyal', 'same', 10, 0.1, 0.2, 0.3, 1.6, 1, 2, 3, 'prior', '{}')""", id, planId);
        return id;
    }

    private void experiment(UUID eventId, UUID planId, UUID planSegmentId) {
        jdbc.update("""
                INSERT INTO audience_experiments (id, org_id, event_id, plan_id, plan_segment_id, arm, seed)
                VALUES (?, ?, ?, ?, ?, 'holdout', 1)""", UUID.randomUUID(), orgId, eventId, planId, planSegmentId);
    }

    private List<UUID> plans(UUID eventId) {
        return jdbc.queryForList("select id from audience_plans where event_id = ? order by created_at", UUID.class,
                eventId);
    }

    private UUID supersededBy(UUID planId) {
        return jdbc.queryForObject("select superseded_by from audience_plans where id = ?", UUID.class, planId);
    }
}
