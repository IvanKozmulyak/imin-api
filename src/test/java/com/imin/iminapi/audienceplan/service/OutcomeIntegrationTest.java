package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.audienceplan.config.PlanRefreshExecutor;
import com.imin.iminapi.audienceplan.repository.OutcomeStore;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The collector, calibration and outcome endpoint; the endpoint reads the app clock, so every time is relative to
 * the test's start. The collector is built over this test's orgs only, as it reads every org's events.
 */
@IminIntegrationTest
class OutcomeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutcomeStore store;
    @Autowired CalibrationService calibration;
    @Autowired AudiencePlanProperties props;
    @Autowired PlatformTransactionManager txManager;
    @Autowired PlanService planService;
    @Autowired com.imin.iminapi.audienceplan.engine.ResponseModel responseModel;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired TicketTierRepository tierRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired PropertyFlips flips;
    @Autowired Clock clock;
    @Autowired @Qualifier(PlanRefreshExecutor.NAME) Executor refreshExecutor;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;

    private final List<UUID> orgs = new ArrayList<>();
    /** Orders experiments of one test by creation, as the invitation writes them in arm order. */
    private int experimentSeq;
    private Instant now;
    private UUID orgA;
    private AuthPrincipal owner;

    @BeforeEach
    void setUp() {
        now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        orgA = org();
        owner = new AuthPrincipal(user(orgA), orgA, UserRole.OWNER, UUID.randomUUID());
        // Platform-wide derived table the collector itself rebuilds wholesale; only this class writes it.
        jdbc.update("delete from response_calibration");
        calibration.invalidate();
    }

    @AfterEach
    void tearDown() {
        AsyncDrain.drain(refreshExecutor);
        AsyncDrain.drain(fanFeatureExecutor);
        for (UUID org : orgs) {
            jdbc.update("delete from campaign_recipients where campaign_id in (select id from campaigns where org_id = ?)", org);
            jdbc.update("delete from audience_outcomes where org_id = ?", org);
            jdbc.update("delete from audience_event_outcomes where org_id = ?", org);
            jdbc.update("delete from audience_assignments where experiment_id in (select id from audience_experiments where org_id = ?)", org);
            jdbc.update("delete from audience_experiments where org_id = ?", org);
            jdbc.update("delete from campaigns where org_id = ?", org);
            jdbc.update("delete from audience_plan_segments where plan_id in (select id from audience_plans where org_id = ?)", org);
            jdbc.update("update audience_plans set superseded_by = null where org_id = ?", org);
            jdbc.update("delete from audience_plans where org_id = ?", org);
            jdbc.update("delete from consent_records where membership_id in (select membership_id from memberships where org_id = ?)", org);
            jdbc.update("delete from tickets where order_id in (select id from orders where org_id = ?)", org);
            jdbc.update("delete from orders where org_id = ?", org);
            List<UUID> consumers = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, org);
            jdbc.update("delete from memberships where org_id = ?", org);
            for (UUID c : consumers) jdbc.update("delete from consumers where consumer_id = ?", c);
            jdbc.update("delete from ticket_tiers where event_id in (select id from events where org_id = ?)", org);
            jdbc.update("delete from events where org_id = ?", org);
            jdbc.update("delete from users where org_id = ?", org);
            jdbc.update("delete from organizations where id = ?", org);
        }
        orgs.clear();
        // Keeps this class's calibration out of other classes' plan numbers.
        jdbc.update("delete from response_calibration");
        calibration.invalidate();
    }

    // ── what counts as bought, sent, attended, unsubscribed, complained ──────

    @Test
    void d1_countsEachArm_byTheOrderConsentAndRecipientRules() throws Exception {
        Past p = pastEvent();
        List<UUID> launch = members(orgA, 6, p.assignedAt.minus(Duration.ofDays(30)));
        List<UUID> held = members(orgA, 2, p.assignedAt.minus(Duration.ofDays(30)));
        UUID segment = planSegment(p.plan, "loyal", "same", "own");
        UUID campaign = campaign(p.event.getId(), "draft", null);
        UUID holdoutX = experiment(p.event.getId(), p.plan, segment, "holdout", null, held, p.assignedAt);
        UUID launchX = experiment(p.event.getId(), p.plan, segment, "launch", campaign, launch, p.assignedAt);

        Instant inWindow = p.assignedAt.plus(Duration.ofDays(1));
        // m0: sent (complained), bought one ticket, complained.
        recipient(campaign, launch.get(0), "complained", p.doorClose.plusSeconds(60));
        order(p.event, launch.get(0), inWindow, false, Ticket.STATE_ISSUED);
        // m1: sent (delivered); bought before the assignment, so not counted; unsubscribed before it too.
        recipient(campaign, launch.get(1), "delivered", inWindow);
        order(p.event, launch.get(1), p.assignedAt.minusSeconds(1), false, Ticket.STATE_ISSUED);
        unsubscribe(launch.get(1), p.assignedAt.minusSeconds(1));
        // m2: bounced (not sent); bought after door close, so not counted.
        recipient(campaign, launch.get(2), "bounced", inWindow);
        order(p.event, launch.get(2), p.doorClose.plusSeconds(1), false, Ticket.STATE_ISSUED);
        // m3: fully refunded.
        order(p.event, launch.get(3), inWindow, false, Ticket.STATE_REFUNDED, Ticket.STATE_REVOKED);
        // m4: partially refunded, the kept ticket scanned at the door.
        order(p.event, launch.get(4), inWindow, false, Ticket.STATE_REFUNDED, Ticket.STATE_REDEEMED);
        // m5: a test-mode order only; unsubscribed after the assignment.
        order(p.event, launch.get(5), inWindow, true, Ticket.STATE_ISSUED);
        unsubscribe(launch.get(5), inWindow);
        // holdout: one bought.
        order(p.event, held.get(0), inWindow, false, Ticket.STATE_ISSUED, Ticket.STATE_ISSUED);

        OutcomeCollector.Result r = collector(now).run();

        assertThat(r.written()).isEqualTo(1);
        assertThat(r.calibrated()).isTrue();
        assertThat(row(launchX)).containsAllEntriesOf(Map.of("members", 6, "sent", 2, "bought", 2, "sent_bought", 1,
                "tickets", 2, "attended", 1, "unsubscribed", 1, "complained", 1));
        assertThat(row(launchX)).containsEntry("phase", "d1").containsEntry("class", "loyal")
                .containsEntry("genre_fit", "same").containsEntry("arm", "launch");
        assertThat(row(holdoutX)).containsAllEntriesOf(Map.of("members", 2, "sent", 0, "bought", 1, "sent_bought", 0,
                "tickets", 2, "attended", 0, "unsubscribed", 0, "complained", 0));

        outcome(p.event).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("d1"))
                .andExpect(jsonPath("$.invited").value(true))
                .andExpect(jsonPath("$.intervalLevel").value(0.8))
                .andExpect(jsonPath("$.minimumForLift").value(60))
                .andExpect(jsonPath("$.computedAt").isNotEmpty())
                .andExpect(jsonPath("$.nextWave").value(nullValue()))
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].classKey").value("loyal"))
                .andExpect(jsonPath("$.segments[0].genreFit").value("same"))
                .andExpect(jsonPath("$.segments[0].plannedRate.low").value(0.1))
                .andExpect(jsonPath("$.segments[0].plannedRate.mid").value(0.2))
                .andExpect(jsonPath("$.segments[0].plannedRate.high").value(0.3))
                .andExpect(jsonPath("$.segments[0].plannedConfidence").value("own"))
                .andExpect(jsonPath("$.segments[0].arms[0].arm").value("holdout"))
                .andExpect(jsonPath("$.segments[0].arms[0].liftStatus").value("baseline"))
                .andExpect(jsonPath("$.segments[0].arms[0].lift").value(nullValue()))
                .andExpect(jsonPath("$.segments[0].arms[1].arm").value("launch"))
                .andExpect(jsonPath("$.segments[0].arms[1].members").value(6))
                .andExpect(jsonPath("$.segments[0].arms[1].sent").value(2))
                .andExpect(jsonPath("$.segments[0].arms[1].bought").value(2))
                .andExpect(jsonPath("$.segments[0].arms[1].tickets").value(2))
                .andExpect(jsonPath("$.segments[0].arms[1].attended").value(1))
                .andExpect(jsonPath("$.segments[0].arms[1].unsubscribed").value(1))
                .andExpect(jsonPath("$.segments[0].arms[1].complained").value(1))
                .andExpect(jsonPath("$.segments[0].arms[1].responseRate.mid").value(0.3333))
                .andExpect(jsonPath("$.segments[0].arms[1].liftStatus").value("too_few"))
                .andExpect(jsonPath("$.segments[0].arms[1].lift").value(nullValue()))
                .andExpect(jsonPath("$..opened").isEmpty())
                .andExpect(jsonPath("$..openRate").isEmpty());
    }

    @Test
    void lift_isARange_whenArmAndHoldoutBothReachTheMinimum() throws Exception {
        Past p = pastEvent();
        List<UUID> launch = members(orgA, 100, p.assignedAt.minus(Duration.ofDays(30)));
        List<UUID> held = members(orgA, 60, p.assignedAt.minus(Duration.ofDays(30)));
        UUID segment = planSegment(p.plan, "first_timer", "same");
        experiment(p.event.getId(), p.plan, segment, "holdout", null, held, p.assignedAt);
        experiment(p.event.getId(), p.plan, segment, "launch", campaign(p.event.getId(), "draft", null), launch,
                p.assignedAt);
        Instant inWindow = p.assignedAt.plus(Duration.ofDays(1));
        for (int i = 0; i < 20; i++) order(p.event, launch.get(i), inWindow, false, Ticket.STATE_ISSUED);
        for (int i = 0; i < 6; i++) order(p.event, held.get(i), inWindow, false, Ticket.STATE_ISSUED);

        collector(now).run();

        outcome(p.event).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].arms[1].liftStatus").value("ok"))
                .andExpect(jsonPath("$.segments[0].arms[1].lift.low").value(0.0236))
                .andExpect(jsonPath("$.segments[0].arms[1].lift.mid").value(0.1))
                .andExpect(jsonPath("$.segments[0].arms[1].lift.high").value(0.1684))
                .andExpect(jsonPath("$.segments[0].arms[1].responseRate.low").value(0.1538))
                .andExpect(jsonPath("$.segments[0].arms[1].responseRate.high").value(0.2559));
    }

    @Test
    void segmentWithoutHoldout_hasNoLift_andSaysSo() throws Exception {
        Past p = pastEvent();
        List<UUID> launch = members(orgA, 40, p.assignedAt.minus(Duration.ofDays(30)));
        UUID segment = planSegment(p.plan, "loyal", "same");
        experiment(p.event.getId(), p.plan, segment, "launch", campaign(p.event.getId(), "draft", null), launch,
                p.assignedAt);

        collector(now).run();

        outcome(p.event).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].arms.length()").value(1))
                .andExpect(jsonPath("$.segments[0].arms[0].liftStatus").value("no_holdout"))
                .andExpect(jsonPath("$.segments[0].arms[0].lift").value(nullValue()));
    }

    @Test
    void anExperimentWithoutPlanSegment_isStoredWithoutClass_andKeptOutOfCalibration() throws Exception {
        Past p = pastEvent();
        List<UUID> launch = members(orgA, 3, p.assignedAt.minus(Duration.ofDays(30)));
        UUID campaign = campaign(p.event.getId(), "draft", null);
        UUID x = experiment(p.event.getId(), null, null, "launch", campaign, launch, p.assignedAt);
        recipient(campaign, launch.get(0), "sent", p.assignedAt.plusSeconds(60));

        collector(now).run();

        assertThat(row(x)).containsEntry("class", null).containsEntry("plan_segment_id", null)
                .containsEntry("sent", 1);
        assertThat(count("select count(*) from response_calibration")).isZero();
        outcome(p.event).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].planSegmentId").value(nullValue()))
                .andExpect(jsonPath("$.segments[0].plannedRate").value(nullValue()))
                .andExpect(jsonPath("$.segments[0].plannedConfidence").value(nullValue()))
                .andExpect(jsonPath("$.segments[0].arms[0].members").value(3));
    }

    // ── new guests ───────────────────────────────────────────────────────────

    @Test
    void newGuests_countsDistinctBuyersWithoutAnEarlierMembership() throws Exception {
        Past p = pastEvent();
        List<UUID> launch = members(orgA, 2, p.assignedAt.minus(Duration.ofDays(30)));
        UUID segment = planSegment(p.plan, "loyal", "same");
        experiment(p.event.getId(), p.plan, segment, "launch", campaign(p.event.getId(), "draft", null), launch,
                p.assignedAt);
        Instant inWindow = p.assignedAt.plus(Duration.ofDays(1));
        // An invited member buying is not new.
        order(p.event, launch.get(0), inWindow, false, Ticket.STATE_ISSUED);
        // A stranger, twice: one new guest.
        orderByEmail(p.event, "stranger-" + UUID.randomUUID() + "@example.com", inWindow, false, 2);
        // A member who joined after the first invitation (e.g. through this purchase) is new.
        UUID late = members(orgA, 1, p.assignedAt.plus(Duration.ofHours(1))).get(0);
        order(p.event, late, inWindow, false, Ticket.STATE_ISSUED);
        // Test-mode and fully refunded strangers are not buyers.
        orderByEmail(p.event, "test-" + UUID.randomUUID() + "@example.com", inWindow, true, 1);
        UUID refunded = members(orgA, 1, p.assignedAt.plus(Duration.ofHours(1))).get(0);
        order(p.event, refunded, inWindow, false, Ticket.STATE_REFUNDED);

        collector(now).run();

        outcome(p.event).andExpect(status().isOk()).andExpect(jsonPath("$.newGuests").value(2));
    }

    // ── idempotency and phases ───────────────────────────────────────────────

    @Test
    void aRerun_changesNothing() {
        Past p = pastEvent();
        List<UUID> launch = members(orgA, 3, p.assignedAt.minus(Duration.ofDays(30)));
        UUID segment = planSegment(p.plan, "loyal", "same");
        UUID campaign = campaign(p.event.getId(), "draft", null);
        UUID x = experiment(p.event.getId(), p.plan, segment, "launch", campaign, launch, p.assignedAt);
        recipient(campaign, launch.get(0), "sent", p.assignedAt.plusSeconds(60));
        order(p.event, launch.get(0), p.assignedAt.plus(Duration.ofDays(1)), false, Ticket.STATE_ISSUED);

        collector(now).run();
        Map<String, Object> first = row(x);
        List<Map<String, Object>> calibrationFirst = calibrationRows();
        OutcomeCollector.Result again = collector(now.plus(Duration.ofHours(1))).run();

        assertThat(again.written()).isZero();
        assertThat(again.unchanged()).isEqualTo(1);
        assertThat(row(x)).isEqualTo(first);
        assertThat(calibrationRows()).isEqualTo(calibrationFirst);
    }

    @Test
    void d7_overwritesD1_andCountsWhatHappenedInTheWeek() throws Exception {
        Past p = pastEvent();
        List<UUID> launch = members(orgA, 3, p.assignedAt.minus(Duration.ofDays(30)));
        UUID segment = planSegment(p.plan, "loyal", "same");
        UUID campaign = campaign(p.event.getId(), "draft", null);
        UUID x = experiment(p.event.getId(), p.plan, segment, "launch", campaign, launch, p.assignedAt);
        // Unsubscribed three days after door close: outside D+1, inside D+7.
        unsubscribe(launch.get(0), p.doorClose.plus(Duration.ofDays(3)));

        collector(now).run();
        assertThat(row(x)).containsEntry("phase", "d1").containsEntry("unsubscribed", 0);

        OutcomeCollector.Result week = collector(p.doorClose.plus(Duration.ofDays(7))).run();

        assertThat(week.written()).isEqualTo(1);
        assertThat(row(x)).containsEntry("phase", "d7").containsEntry("unsubscribed", 1);
        assertThat(count("select count(*) from audience_outcomes where event_id = ?", p.event.getId())).isEqualTo(1);
        assertThat(collector(p.doorClose.plus(Duration.ofDays(8))).run().unchanged()).isEqualTo(1);
        outcome(p.event).andExpect(jsonPath("$.phase").value("d7"));
    }

    @Test
    void beforeOneDayAfterDoorClose_nothingIsStored_andTheEndpointSaysPending() throws Exception {
        Event e = event(orgA, now.minus(Duration.ofHours(20)), null);
        UUID plan = plan(e.getId());
        UUID segment = planSegment(plan, "loyal", "same");
        experiment(e.getId(), plan, segment, "launch", campaign(e.getId(), "draft", null),
                members(orgA, 2, now.minus(Duration.ofDays(40))), now.minus(Duration.ofDays(10)));

        OutcomeCollector.Result r = collector(now).run();

        assertThat(r.written()).isZero();
        assertThat(count("select count(*) from audience_event_outcomes where event_id = ?", e.getId())).isZero();
        outcome(e).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("pending"))
                .andExpect(jsonPath("$.invited").value(true))
                .andExpect(jsonPath("$.segments.length()").value(0))
                .andExpect(jsonPath("$.newGuests").value(nullValue()))
                .andExpect(jsonPath("$.computedAt").value(nullValue()))
                .andExpect(jsonPath("$.doorClosesAt").isNotEmpty());
    }

    @Test
    void livePhase_computesOnRequest_withoutWriting_andShowsTheNextWave() throws Exception {
        Event e = event(orgA, now.plus(Duration.ofDays(2)), null);
        UUID plan = plan(e.getId());
        UUID segment = planSegment(plan, "loyal", "same");
        List<UUID> launch = members(orgA, 2, now.minus(Duration.ofDays(40)));
        List<UUID> d3 = members(orgA, 2, now.minus(Duration.ofDays(40)));
        Instant assigned = now.minus(Duration.ofDays(5));
        Instant wave = now.plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
        UUID launchCampaign = campaign(e.getId(), "sent", null);
        experiment(e.getId(), plan, segment, "launch", launchCampaign, launch, assigned);
        experiment(e.getId(), plan, segment, "d3", campaign(e.getId(), "scheduled", wave), d3, assigned);
        recipient(launchCampaign, launch.get(0), "delivered", assigned.plusSeconds(60));
        order(e, launch.get(0), now.minus(Duration.ofDays(1)), false, Ticket.STATE_ISSUED);

        outcome(e).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("live"))
                .andExpect(jsonPath("$.invited").value(true))
                .andExpect(jsonPath("$.segments[0].plannedConfidence").value("prior"))
                .andExpect(jsonPath("$.computedAt").value(nullValue()))
                .andExpect(jsonPath("$.nextWave").value(wave.toString()))
                .andExpect(jsonPath("$.newGuests").value(0))
                .andExpect(jsonPath("$.segments[0].arms[0].arm").value("launch"))
                .andExpect(jsonPath("$.segments[0].arms[0].sent").value(1))
                .andExpect(jsonPath("$.segments[0].arms[0].bought").value(1))
                .andExpect(jsonPath("$.segments[0].arms[1].arm").value("d3"))
                .andExpect(jsonPath("$.segments[0].arms[1].bought").value(0));

        assertThat(count("select count(*) from audience_outcomes where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from audience_event_outcomes where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from response_calibration")).isZero();
    }

    @Test
    void anEventWithoutExperiments_isLiveWithNoSegments_andNoNewGuestFigure() throws Exception {
        Event e = event(orgA, now.plus(Duration.ofDays(2)), null);

        outcome(e).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("live"))
                .andExpect(jsonPath("$.invited").value(false))
                .andExpect(jsonPath("$.segments.length()").value(0))
                .andExpect(jsonPath("$.newGuests").value(nullValue()))
                .andExpect(jsonPath("$.nextWave").value(nullValue()));
    }

    // ── cancelled and deleted events ─────────────────────────────────────────

    @Test
    void aCancelledEvent_isNotCollected() {
        Past p = invitedPastEvent();
        jdbc.update("update events set status = 'CANCELLED' where id = ?", p.event.getId());

        collector(now).run();

        assertNotCollected(p);
    }

    @Test
    void aSoftDeletedEvent_isNotCollected() {
        Past p = invitedPastEvent();
        jdbc.update("update events set deleted_at = ? where id = ?", ts(now), p.event.getId());

        collector(now).run();

        assertNotCollected(p);
    }

    @Test
    void anEventCancelledAfterCollection_leavesTheCalibration() {
        Past p = invitedPastEvent();
        collector(now).run();
        assertThat(count("select count(*) from response_calibration where org_id = ?", orgA)).isEqualTo(1);

        jdbc.update("update events set status = 'CANCELLED' where id = ?", p.event.getId());
        OutcomeCollector.Result r = collector(now.plus(Duration.ofHours(1))).run();

        assertThat(r.calibrated()).isTrue();
        assertThat(count("select count(*) from response_calibration")).isZero();
    }

    @Test
    void anEventDeletedAfterCollection_leavesTheCalibration() {
        Past p = invitedPastEvent();
        collector(now).run();
        assertThat(count("select count(*) from response_calibration where org_id = ?", orgA)).isEqualTo(1);

        jdbc.update("update events set deleted_at = ? where id = ?", ts(now), p.event.getId());
        OutcomeCollector.Result r = collector(now.plus(Duration.ofHours(1))).run();

        assertThat(r.calibrated()).isTrue();
        assertThat(count("select count(*) from response_calibration")).isZero();
    }

    /** A past event with one sent launch arm, so it would reach the calibration. */
    private Past invitedPastEvent() {
        Past p = pastEvent();
        List<UUID> launch = members(orgA, 2, p.assignedAt.minus(Duration.ofDays(30)));
        UUID segment = planSegment(p.plan, "loyal", "same");
        UUID campaign = campaign(p.event.getId(), "sent", null);
        experiment(p.event.getId(), p.plan, segment, "launch", campaign, launch, p.assignedAt);
        recipient(campaign, launch.get(0), "delivered", p.assignedAt.plusSeconds(60));
        return p;
    }

    private void assertNotCollected(Past p) {
        assertThat(store.eventsWithExperiments(now.minus(Duration.ofDays(40)), now))
                .noneMatch(ref -> ref.eventId().equals(p.event.getId()));
        assertThat(count("select count(*) from audience_event_outcomes where event_id = ?", p.event.getId())).isZero();
        assertThat(count("select count(*) from audience_outcomes where event_id = ?", p.event.getId())).isZero();
        assertThat(count("select count(*) from response_calibration")).isZero();
    }

    @Test
    void aPastEventWithoutExperiments_isPending_andNotInvited() throws Exception {
        Event e = event(orgA, now.minus(Duration.ofDays(3)), null);

        collector(now).run();

        outcome(e).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("pending"))
                .andExpect(jsonPath("$.invited").value(false))
                .andExpect(jsonPath("$.segments.length()").value(0));
    }

    // ── the kill switch and org scope ────────────────────────────────────────

    @Test
    void aDisabledOrg_isSkippedByTheJob() {
        Past p = pastEvent();
        UUID segment = planSegment(p.plan, "loyal", "same");
        experiment(p.event.getId(), p.plan, segment, "launch", campaign(p.event.getId(), "draft", null),
                members(orgA, 2, p.assignedAt.minus(Duration.ofDays(30))), p.assignedAt);

        OutcomeCollector.Result r = collector(now, Set.of(UUID.randomUUID())).run();

        assertThat(r.written()).isZero();
        assertThat(r.skipped()).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from audience_event_outcomes where event_id = ?", p.event.getId())).isZero();
    }

    @Test
    void killSwitchOff_is404() throws Exception {
        Past p = pastEvent();
        flips.set(props, "enabled", false);

        outcome(p.event).andExpect(status().isNotFound());
    }

    @Test
    void anotherOrgsEvent_is404() throws Exception {
        UUID orgB = org();
        Event other = event(orgB, now.minus(Duration.ofDays(3)), null);

        outcome(other).andExpect(status().isNotFound());
    }

    @Test
    void openapi_publishesTheMarker_withNestedNamesThatDoNotReplaceTheInvitationArm() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/events/{eventId}/audience-plan/outcome'].get").exists())
                .andExpect(jsonPath("$.components.schemas.AudiencePlanOutcomeResponse.properties.newGuests").exists())
                .andExpect(jsonPath("$.components.schemas.AudiencePlanOutcomeResponse.properties.invited").exists())
                .andExpect(jsonPath("$.components.schemas.AudiencePlanOutcomeSegment.properties.plannedConfidence")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.AudiencePlanOutcomeArm.properties.liftStatus").exists())
                .andExpect(jsonPath("$.components.schemas.AudiencePlanOutcomeArm.properties.opened").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.AudiencePlanOutcomeSegment.properties.plannedRate").exists())
                .andExpect(jsonPath("$.components.schemas.AudiencePlanOutcomeRange.properties.mid").exists())
                .andExpect(jsonPath("$.components.schemas.Arm.properties.draftMissing").exists());
    }

    // ── calibration ──────────────────────────────────────────────────────────

    @Test
    void calibration_holdsAggregatesOnly_perClassFitAndArm_andSkipsUnsentArms() {
        Past p = pastEvent();
        List<UUID> launch = members(orgA, 4, p.assignedAt.minus(Duration.ofDays(30)));
        List<UUID> d3 = members(orgA, 3, p.assignedAt.minus(Duration.ofDays(30)));
        List<UUID> held = members(orgA, 2, p.assignedAt.minus(Duration.ofDays(30)));
        UUID segment = planSegment(p.plan, "loyal", "same");
        UUID campaign = campaign(p.event.getId(), "sent", null);
        experiment(p.event.getId(), p.plan, segment, "holdout", null, held, p.assignedAt);
        experiment(p.event.getId(), p.plan, segment, "launch", campaign, launch, p.assignedAt);
        experiment(p.event.getId(), p.plan, segment, "d3", campaign(p.event.getId(), "draft", null), d3, p.assignedAt);
        Instant inWindow = p.assignedAt.plus(Duration.ofDays(1));
        for (int i = 0; i < 3; i++) recipient(campaign, launch.get(i), "delivered", inWindow);
        order(p.event, launch.get(0), inWindow, false, Ticket.STATE_ISSUED);
        order(p.event, launch.get(3), inWindow, false, Ticket.STATE_ISSUED);
        order(p.event, d3.get(0), inWindow, false, Ticket.STATE_ISSUED);

        collector(now).run();

        assertThat(calibrationRows()).containsExactlyInAnyOrder(
                cal("imin", OutcomeStore.IMIN_ORG, "holdout", 2, 0),
                cal("imin", OutcomeStore.IMIN_ORG, "launch", 3, 1),
                cal("org", orgA, "holdout", 2, 0),
                cal("org", orgA, "launch", 3, 1));
        List<String> columns = jdbc.queryForList(
                "select lower(column_name) from information_schema.columns where lower(table_name) = 'response_calibration'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder("id", "scope", "org_id", "class", "genre_fit", "arm", "n",
                "bought", "events", "updated_at");
        List<String> outcomeColumns = jdbc.queryForList(
                "select lower(column_name) from information_schema.columns where lower(table_name) in"
                        + " ('audience_outcomes', 'audience_event_outcomes')", String.class);
        assertThat(outcomeColumns).doesNotContain("membership_id", "email", "consumer_id");
    }

    @Test
    void storedCalibration_movesTheOwnBand_andChangesThePlansInputs() {
        Event future = event(orgA, now.plus(Duration.ofDays(20)), null);
        tier(future.getId(), 300);
        planService.current(orgA, future.getId(), "en");
        UUID before = currentPlan(future.getId());
        assertThat(count("select calibration_version from audience_plans where id = ?", before)).isZero();

        Past p = pastEvent();
        List<UUID> launch = members(orgA, 20, p.assignedAt.minus(Duration.ofDays(30)));
        UUID segment = planSegment(p.plan, "loyal", "same");
        UUID campaign = campaign(p.event.getId(), "sent", null);
        experiment(p.event.getId(), p.plan, segment, "launch", campaign, launch, p.assignedAt);
        for (UUID m : launch) recipient(campaign, m, "delivered", p.assignedAt.plusSeconds(60));
        for (int i = 0; i < 8; i++) order(p.event, launch.get(i), p.assignedAt.plus(Duration.ofDays(1)), false,
                Ticket.STATE_ISSUED);

        collector(now).run();

        assertThat(calibration.observations(orgA, "loyal", com.imin.iminapi.audienceplan.engine.ResponseModel.Fit.SAME)
                .own()).isEqualTo(new com.imin.iminapi.audienceplan.engine.CalibrationSource.Counts(20, 8));
        assertThat(calibration.version()).isNotZero();
        assertThat(responseModel.calibrationVersion()).isEqualTo(calibration.version());
        planService.current(orgA, future.getId(), "en");
        UUID after = currentPlan(future.getId());
        assertThat(after).isNotEqualTo(before);
        assertThat(count("select calibration_version from audience_plans where id = ?", after))
                .isEqualTo(calibration.version());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A house night that started 3 days ago (door closed 2.5 days ago), with a plan, invited 20 days ago. */
    private record Past(Event event, UUID plan, Instant doorClose, Instant assignedAt) {}

    private Past pastEvent() {
        Instant start = now.minus(Duration.ofDays(3));
        Event e = event(orgA, start, null);
        return new Past(e, plan(e.getId()), start.plus(Duration.ofHours(12)), now.minus(Duration.ofDays(20)));
    }

    private OutcomeCollector collector(Instant at) {
        return collector(at, Set.copyOf(orgs));
    }

    /** Only {@code allowed} orgs are collected, so another class's leftover events never enter the counts. */
    private OutcomeCollector collector(Instant at, Set<UUID> allowed) {
        AudiencePlanProperties local = new AudiencePlanProperties();
        local.setBetaOrgIds(allowed);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutcomeCollector> self = mock(ObjectProvider.class);
        return new OutcomeCollector(store, calibration, new AudiencePlanAccess(local), txManager,
                Clock.fixed(at, ZoneOffset.UTC), self);
    }

    private ResultActions outcome(Event e) throws Exception {
        return mvc.perform(get("/api/v1/events/" + e.getId() + "/audience-plan/outcome").with(auth(owner)));
    }

    private Map<String, Object> row(UUID experimentId) {
        Map<String, Object> raw = jdbc.queryForMap(
                "select plan_segment_id, class, genre_fit, arm, phase, members, sent, bought, sent_bought, tickets,"
                        + " attended, unsubscribed, complained, computed_at from audience_outcomes where experiment_id = ?",
                experimentId);
        Map<String, Object> out = new java.util.HashMap<>();
        raw.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v instanceof Number n ? n.intValue() : v));
        return out;
    }

    private List<Map<String, Object>> calibrationRows() {
        return jdbc.queryForList("select scope, org_id, class, genre_fit, arm, n, bought from response_calibration")
                .stream().map(m -> {
                    Map<String, Object> out = new java.util.HashMap<>();
                    m.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT),
                            v instanceof Number n ? n.intValue() : v));
                    return out;
                }).toList();
    }

    private static Map<String, Object> cal(String scope, UUID org, String arm, int n, int bought) {
        return Map.of("scope", scope, "org_id", org, "class", "loyal", "genre_fit", "same", "arm", arm, "n", n,
                "bought", bought);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private UUID currentPlan(UUID eventId) {
        return jdbc.queryForObject("select id from audience_plans where event_id = ? and superseded_by is null",
                UUID.class, eventId);
    }

    private UUID plan(UUID eventId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into audience_plans (id, org_id, event_id, mode, capacity, target_pct, target_tickets,
                    tickets_per_order, excluded_segments, mailable, verdict, gap_low, gap_high, reach_needed,
                    small_groups_not_shown, other_genre_invited, other_genre_held_back, exclusions, today_date,
                    event_date, launch_date, event_started, actions, logic_version, priors_version,
                    calibration_version, inputs_hash)
                values (?, ?, ?, 'warm', 100, 85, 85, 1.6, '[]', 10, 'weak', 0, 0, '{}', 0, false, 0, '{}',
                    DATE '2026-09-01', DATE '2026-10-01', DATE '2026-09-01', false, '[]', 1, 1, 0, 'hash')""",
                id, orgOf(eventId), eventId);
        return id;
    }

    private UUID planSegment(UUID plan, String classKey, String fit) {
        return planSegment(plan, classKey, fit, "prior");
    }

    private UUID planSegment(UUID plan, String classKey, String fit, String confidence) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into audience_plan_segments (id, plan_id, position, class, genre_fit, mailable, rate_low,
                    rate_mid, rate_high, tickets_per_order, expected_low, expected_mid, expected_high, confidence, reason)
                values (?, ?, 0, ?, ?, 10, 0.1, 0.2, 0.3, 1.6, 1, 2, 3, ?, '{}')""", id, plan, classKey, fit,
                confidence);
        return id;
    }

    private UUID experiment(UUID eventId, UUID plan, UUID segment, String arm, UUID campaign, List<UUID> members,
                            Instant assignedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into audience_experiments (id, org_id, event_id, plan_id, plan_segment_id, arm, campaign_id,
                    members, seed, holdout_pct, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, 1, 15, ?)""",
                id, orgOf(eventId), eventId, plan, segment, arm, campaign, members.size(),
                ts(assignedAt.plusMillis(experimentSeq++)));
        for (UUID m : members) {
            jdbc.update("insert into audience_assignments (experiment_id, membership_id, arm, assigned_at)"
                    + " values (?, ?, ?, ?)", id, m, arm, ts(assignedAt));
        }
        return id;
    }

    private UUID campaign(UUID eventId, String status, Instant scheduledAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into campaigns (id, org_id, channel, name, status, origin, event_id, scheduled_at)
                values (?, ?, 'email', 'Audience plan arm', ?, 'audience_plan', ?, ?)""",
                id, orgOf(eventId), status, eventId, scheduledAt == null ? null : ts(scheduledAt));
        return id;
    }

    private void recipient(UUID campaign, UUID membershipId, String status, Instant at) {
        jdbc.update("""
                insert into campaign_recipients (id, campaign_id, membership_id, email, status, last_event_at)
                values (?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), campaign, membershipId, email(membershipId), status,
                ts(at));
    }

    private void unsubscribe(UUID membershipId, Instant at) {
        jdbc.update("""
                insert into consent_records (id, membership_id, channel, status, lawful_basis, source, occurred_at)
                values (?, ?, 'email', 'unsubscribed', null, 'footer_link', ?)""", UUID.randomUUID(), membershipId,
                ts(at));
    }

    private void order(Event e, UUID membershipId, Instant at, boolean testMode, String... ticketStates) {
        writeOrder(e, email(membershipId), at, testMode, ticketStates);
    }

    private void orderByEmail(Event e, String email, Instant at, boolean testMode, int orders) {
        for (int i = 0; i < orders; i++) writeOrder(e, email, at, testMode, Ticket.STATE_ISSUED);
    }

    private void writeOrder(Event e, String email, Instant at, boolean testMode, String... ticketStates) {
        Order o = new Order();
        o.setToken("outcome-" + UUID.randomUUID());
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail(email);
        o.setTotalMinor(2000L);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setTestMode(testMode);
        o.setCreatedAt(at);
        o = orderRepo.save(o);
        for (String state : ticketStates) {
            Ticket t = new Ticket();
            t.setToken("outcome-t-" + UUID.randomUUID());
            t.setOrderId(o.getId());
            t.setEventId(e.getId());
            t.setTierId(UUID.randomUUID());
            t.setTierName("GA");
            t.setPriceMinor(2000);
            t.setState(state);
            ticketRepo.save(t);
        }
    }

    private String email(UUID membershipId) {
        return jdbc.queryForObject("select c.normalized_email from memberships m join consumers c"
                + " on m.consumer_id = c.consumer_id where m.membership_id = ?", String.class, membershipId);
    }

    private UUID orgOf(UUID eventId) {
        return jdbc.queryForObject("select org_id from events where id = ?", UUID.class, eventId);
    }

    private List<UUID> members(UUID orgId, int n, Instant createdAt) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Consumer c = new Consumer();
            c.setNormalizedEmail("outcome-" + UUID.randomUUID() + "@example.com");
            Membership m = new Membership();
            m.setOrgId(orgId);
            m.setConsumerId(consumerRepo.save(c).getConsumerId());
            m.setCreatedAt(createdAt);
            out.add(membershipRepo.save(m).getMembershipId());
        }
        return out;
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("Outcome Org");
        o.setSlug("outcome-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("outcome@example.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    private UUID user(UUID orgId) {
        User u = new User();
        u.setEmail("outcome-owner-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        return userRepo.save(u).getId();
    }

    private Event event(UUID orgId, Instant startsAt, Instant endsAt) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Outcome Night");
        e.setSlug("outcome-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setGenre("House & Techno");
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(startsAt.minus(Duration.ofDays(60)));
        e.setCreatedBy(orgId.equals(orgA) ? owner.userId() : user(orgId));
        e.setCurrency("EUR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(startsAt);
        e.setEndsAt(endsAt);
        return eventRepo.save(e);
    }

    private void tier(UUID eventId, int quantity) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(2000);
        t.setQuantity(quantity);
        t.setEnabled(true);
        tierRepo.save(t);
    }

    private static Timestamp ts(Instant at) {
        return Timestamp.from(at);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
