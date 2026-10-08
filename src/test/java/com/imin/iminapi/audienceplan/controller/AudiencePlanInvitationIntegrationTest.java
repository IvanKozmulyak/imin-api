package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.PlanPopulation;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.RecipientMaterializer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/events/{eventId}/audience-plan/invitations}. The org holds the checked warm fixture as real
 * plan-mailable members, so the consent gate and the loader run for real.
 */
@IminIntegrationTest
class AudiencePlanInvitationIntegrationTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String HOUSE = "house & techno";
    private static final String BOTH_SEGMENTS = """
            {"segments":[
              {"classKey":"first_timer","genreFit":"same","arms":["launch","d3"],"holdoutPct":15},
              {"class":"loyal","genreFit":"same","arms":["d3","launch"]}
            ]}""";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired TicketTierRepository tierRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired TransactionTemplate tx;
    @Autowired CampaignRepository campaignRepo;
    @Autowired RecipientMaterializer materializer;
    @Autowired AudiencePlanProperties props;
    @Autowired AudiencePlanLogic logic;
    @Autowired ConsentService consentService;
    @Autowired DataSource dataSource;
    @Autowired Clock clock;
    @Autowired PropertyFlips flips;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;

    private final List<UUID> orgs = new ArrayList<>();
    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal owner;
    private LocalDate today;
    private List<UUID> loyal;
    private List<UUID> repeat;
    private List<UUID> firstTimers;
    private boolean populated;

    @BeforeEach
    void setUp() {
        orgA = org();
        orgB = org();
        owner = new AuthPrincipal(user(orgA), orgA, UserRole.OWNER, UUID.randomUUID());
        today = LocalDate.now(PARIS);
        loyal = members(orgA, 40);
        repeat = members(orgA, 70);
        firstTimers = members(orgA, 235);
    }

    @AfterEach
    void tearDown() {
        // A consent change recomputes features after commit; let it land before the members go.
        AsyncDrain.drain(fanFeatureExecutor);
        for (UUID org : orgs) {
            jdbc.update("delete from marketing_optouts where org_id = ?", org);
            jdbc.update("delete from campaign_recipients where campaign_id in (select id from campaigns where org_id = ?)", org);
            jdbc.update("delete from audience_assignments where experiment_id in (select id from audience_experiments where org_id = ?)", org);
            jdbc.update("delete from audience_experiments where org_id = ?", org);
            jdbc.update("delete from campaigns where org_id = ?", org);
            jdbc.update("delete from segments where org_id = ?", org);
            jdbc.update("delete from audience_plan_segments where plan_id in (select id from audience_plans where org_id = ?)", org);
            jdbc.update("update audience_plans set superseded_by = null where org_id = ?", org);
            jdbc.update("delete from audience_plans where org_id = ?", org);
            List<UUID> consumers = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, org);
            jdbc.update("delete from memberships where org_id = ?", org);
            jdbc.batchUpdate("delete from consumers where consumer_id = ?", consumers.stream().map(c -> new Object[] {c}).toList());
            jdbc.update("delete from ticket_tiers where event_id in (select id from events where org_id = ?)", org);
            jdbc.update("delete from events where org_id = ?", org);
            jdbc.update("delete from users where org_id = ?", org);
            jdbc.update("delete from organizations where id = ?", org);
        }
        orgs.clear();
    }

    // ── the split through the API ─────────────────────────────────────────────

    @Test
    void invite_splitsHoldoutAndArms_andWritesDraftsOnly() throws Exception {
        Event e = plannedEvent(28);

        invite(e, BOTH_SEGMENTS).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(e.getId().toString()))
                .andExpect(jsonPath("$.sendsEnabled").value(false))
                .andExpect(jsonPath("$.invitations.length()").value(2))
                .andExpect(jsonPath("$.invitations[0].classKey").value("first_timer"))
                .andExpect(jsonPath("$.invitations[0].genreFit").value("same"))
                .andExpect(jsonPath("$.invitations[0].members").value(235))
                .andExpect(jsonPath("$.invitations[0].created").value(true))
                .andExpect(jsonPath("$.invitations[0].holdout.members").value(35))
                .andExpect(jsonPath("$.invitations[0].arms.length()").value(2))
                .andExpect(jsonPath("$.invitations[0].arms[0].arm").value("launch"))
                .andExpect(jsonPath("$.invitations[0].arms[0].members").value(100))
                .andExpect(jsonPath("$.invitations[0].arms[1].arm").value("d3"))
                .andExpect(jsonPath("$.invitations[0].arms[1].members").value(100))
                .andExpect(jsonPath("$.invitations[1].classKey").value("loyal"))
                .andExpect(jsonPath("$.invitations[1].members").value(40))
                .andExpect(jsonPath("$.invitations[1].holdout").value(nullValue()))
                .andExpect(jsonPath("$.invitations[1].arms[0].arm").value("launch"))
                .andExpect(jsonPath("$.invitations[1].arms[0].members").value(20))
                .andExpect(jsonPath("$.invitations[1].arms[1].arm").value("d3"))
                .andExpect(jsonPath("$.invitations[1].arms[1].members").value(20));

        UUID planId = currentPlan(e);
        // 1 holdout + 2 arms for first-timers, 2 arms for loyal; everyone in the two segments is assigned once.
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(5);
        assertThat(count("select count(*) from audience_experiments where event_id = ? and plan_id = ?", e.getId(), planId))
                .isEqualTo(5);
        assertThat(count("""
                select count(*) from audience_assignments a join audience_experiments x on x.id = a.experiment_id
                 where x.event_id = ?""", e.getId())).isEqualTo(275);
        assertThat(assigned(e)).containsExactlyInAnyOrderElementsOf(concat(loyal, firstTimers));
        assertThat(count("select count(distinct seed) from audience_experiments where plan_segment_id = ?",
                planSegment(planId, "first_timer"))).isEqualTo(1);

        for (Map<String, Object> x : jdbc.queryForList(
                "select id, arm, campaign_id, members from audience_experiments where event_id = ? and arm <> 'holdout'",
                e.getId())) {
            UUID campaignId = (UUID) x.get("campaign_id");
            Map<String, Object> c = jdbc.queryForMap(
                    "select origin, status, event_id, segment_id, channel, scheduled_at from campaigns where id = ?", campaignId);
            assertThat(c.get("origin")).isEqualTo("audience_plan");
            assertThat(c.get("status")).isEqualTo("draft");
            assertThat(c.get("event_id")).isEqualTo(e.getId());
            assertThat(c.get("channel")).isEqualTo("email");
            assertThat(c.get("scheduled_at")).isNull();
            Map<String, Object> s = jdbc.queryForMap("select origin, kind, snapshot_ids from segments where id = ?",
                    c.get("segment_id"));
            assertThat(s.get("origin")).isEqualTo("audience_plan");
            assertThat(s.get("kind")).isEqualTo("static");
            List<UUID> armMembers = jdbc.queryForList(
                    "select membership_id from audience_assignments where experiment_id = ?", UUID.class, x.get("id"));
            assertThat(armMembers).hasSize(((Number) x.get("members")).intValue());
            String snapshot = String.valueOf(s.get("snapshot_ids"));
            for (UUID m : armMembers) assertThat(snapshot).contains(m.toString());
        }
        assertThat(count("select count(*) from campaigns where org_id = ? and status <> 'draft'", orgA)).isZero();
        assertThat(count("select count(*) from campaign_recipients where campaign_id in (select id from campaigns where org_id = ?)",
                orgA)).isZero();
    }

    @Test
    void holdoutMembers_getNoRecipientRow_whenAnArmCampaignMaterialises() throws Exception {
        Event e = plannedEvent(28);
        invite(e, BOTH_SEGMENTS).andExpect(status().isOk());

        UUID holdout = jdbc.queryForObject(
                "select id from audience_experiments where event_id = ? and arm = 'holdout'", UUID.class, e.getId());
        Set<UUID> heldOut = new HashSet<>(jdbc.queryForList(
                "select membership_id from audience_assignments where experiment_id = ?", UUID.class, holdout));
        assertThat(heldOut).hasSize(35);
        for (UUID campaignId : jdbc.queryForList(
                "select campaign_id from audience_experiments where event_id = ? and arm <> 'holdout'", UUID.class, e.getId())) {
            materializer.materialize(campaignRepo.findById(campaignId).orElseThrow());
        }
        List<UUID> recipients = jdbc.queryForList("""
                select membership_id from campaign_recipients
                 where campaign_id in (select id from campaigns where org_id = ?)""", UUID.class, orgA);
        assertThat(recipients).hasSize(240).doesNotContainAnyElementsOf(heldOut);
    }

    /** Owns the end-to-end path: arm segment, real SendGate, real SendPathGuard; the individual skip reasons are owned by the SendGate and SendPathGuardMaterialize tests. */
    @Test
    void membersWhoWithdrawAfterTheInvitation_areSkipped_andNeverPending() throws Exception {
        Event e = plannedEvent(28);
        String body = invite(e, BOTH_SEGMENTS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID experiment = UUID.fromString(JsonPath.read(body, "$.invitations[1].arms[0].experimentId"));
        UUID campaignId = UUID.fromString(JsonPath.read(body, "$.invitations[1].arms[0].campaignId"));
        List<UUID> armMembers = jdbc.queryForList(
                "select membership_id from audience_assignments where experiment_id = ?", UUID.class, experiment);
        UUID leaver = armMembers.get(0);
        UUID objector = armMembers.get(1);
        consentService.unsubscribe(orgA, leaver, "one_click", ConsentOrigin.DATA_SUBJECT, null);
        // The profiling objection alone: the send gate still admits this member, only the consent gate does not.
        jdbc.update("update memberships set objected_profiling = true where membership_id = ?", objector);

        materializer.materialize(campaignRepo.findById(campaignId).orElseThrow());

        assertThat(jdbc.queryForMap("select status, skip_reason from campaign_recipients where campaign_id = ?"
                + " and membership_id = ?", campaignId, leaver))
                .containsEntry("status", "skipped").containsEntry("skip_reason", "marketing_unsubscribed");
        assertThat(jdbc.queryForMap("select status, skip_reason from campaign_recipients where campaign_id = ?"
                + " and membership_id = ?", campaignId, objector))
                .containsEntry("status", "skipped").containsEntry("skip_reason", "consent_gate");
        assertThat(jdbc.queryForList("select membership_id from campaign_recipients where campaign_id = ?"
                + " and status = 'pending'", UUID.class, campaignId))
                .containsExactlyInAnyOrderElementsOf(armMembers.subList(2, armMembers.size()));
    }

    @Test
    void armSegments_areHiddenFromTheSegmentList_andCannotBeDeleted() throws Exception {
        Event e = plannedEvent(28);
        String body = invite(e, BOTH_SEGMENTS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String armSegment = JsonPath.read(body, "$.invitations[0].arms[0].segmentId");

        String list = mvc.perform(get("/api/v1/audience/segments").with(auth(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(list, "$[*].id");
        assertThat(ids).isNotEmpty().doesNotContain(armSegment);
        assertThat(count("select count(*) from segments where org_id = ? and origin = 'organizer'", orgA))
                .isEqualTo(ids.size());

        mvc.perform(delete("/api/v1/audience/segments/" + armSegment).with(auth(owner)))
                .andExpect(status().isNotFound());
        assertThat(count("select count(*) from segments where id = ?", UUID.fromString(armSegment))).isEqualTo(1);
    }

    @Test
    void anArmDraft_cannotBeSent_whileSendsAreDisabled() throws Exception {
        Event e = plannedEvent(28);
        String body = invite(e, BOTH_SEGMENTS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String campaignId = JsonPath.read(body, "$.invitations[0].arms[0].campaignId");

        mvc.perform(post("/api/v1/marketing/campaigns/" + campaignId + "/send").with(auth(owner))
                        .header("Idempotency-Key", "idem-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIENCE_SENDS_DISABLED"));
        assertThat(jdbc.queryForObject("select status from campaigns where id = ?", String.class,
                UUID.fromString(campaignId))).isEqualTo("draft");
    }

    // ── idempotency ─────────────────────────────────────────────────────────

    @Test
    void aRepeatCall_returnsTheSameIds_andWritesNothing() throws Exception {
        Event e = plannedEvent(28);
        String first = invite(e, BOTH_SEGMENTS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int experiments = count("select count(*) from audience_experiments where event_id = ?", e.getId());
        int campaigns = count("select count(*) from campaigns where org_id = ?", orgA);
        int segments = count("select count(*) from segments where org_id = ?", orgA);

        String second = invite(e, BOTH_SEGMENTS).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].created").value(false))
                .andExpect(jsonPath("$.invitations[1].created").value(false))
                .andReturn().getResponse().getContentAsString();

        assertSameIds(first, second);
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(experiments);
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isEqualTo(campaigns);
        assertThat(count("select count(*) from segments where org_id = ?", orgA)).isEqualTo(segments);
    }

    @Test
    void twoConcurrentCalls_waitOnTheEventLock_andInviteOnce() throws Exception {
        Event e = plannedEvent(28);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return invite(e, BOTH_SEGMENTS).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                }));
            }
            start.countDown();
            String a = calls.get(0).get(60, TimeUnit.SECONDS);
            String b = calls.get(1).get(60, TimeUnit.SECONDS);
            assertSameIds(a, b);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(5);
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isEqualTo(4);
    }

    @Test
    void twoCallsOnDifferentPlanGenerations_serialiseOnTheEventLock_andInviteOnce() throws Exception {
        Event e = plannedEvent(28);
        ExecutorService a = Executors.newSingleThreadExecutor();
        ExecutorService b = Executors.newSingleThreadExecutor();
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "audience_experiments", "event_id", e.getId())) {
            try {
                UUID oldPlan = currentPlan(e);
                Future<String> first = a.submit(() -> invite(e, BOTH_SEGMENTS).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
                // A holds the event lock on plan generation 1, paused at its first experiment write.
                pause.awaitBlocked(Duration.ofSeconds(20));
                mvc.perform(post(planUrl(e)).with(auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPct\":90}")).andExpect(status().isOk());
                assertThat(currentPlan(e)).isNotEqualTo(oldPlan);

                Future<String> second = b.submit(() -> invite(e, BOTH_SEGMENTS).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
                awaitEventLockWaiter(e);
                assertThat(second.isDone()).as("B waits for A's event lock").isFalse();
                pause.release();
                String x = first.get(60, TimeUnit.SECONDS);
                String y = second.get(60, TimeUnit.SECONDS);
                assertSameIds(x, y);
                assertThat((Object) JsonPath.read(y, "$.invitations[0].planId")).isEqualTo(oldPlan.toString());
            } finally {
                pause.release();
                a.shutdownNow();
                b.shutdownNow();
                a.awaitTermination(30, TimeUnit.SECONDS);
                b.awaitTermination(30, TimeUnit.SECONDS);
            }
        }
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(5);
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isEqualTo(4);
    }

    /** Waits until a transaction is queued on the event's advisory lock (PlanService.lockFirstPlan). */
    private void awaitEventLockWaiter(Event e) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (count("select count(*) from pg_locks where locktype = 'advisory' and objsubid = 1 and not granted"
                    + " and ((classid::bigint << 32) | objid::bigint) = hashtextextended(cast(? as text), 0)",
                    e.getId().toString()) > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no call waited on the event lock of " + e.getId());
    }

    @Test
    void afterAPlanRecompute_theSameClassAndFit_isNotInvitedTwice() throws Exception {
        Event e = plannedEvent(28);
        String first = invite(e, BOTH_SEGMENTS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID oldPlan = currentPlan(e);
        mvc.perform(post(planUrl(e)).with(auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetPct\":90}")).andExpect(status().isOk());
        assertThat(currentPlan(e)).isNotEqualTo(oldPlan);

        String second = invite(e, BOTH_SEGMENTS).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].planId").value(oldPlan.toString()))
                .andReturn().getResponse().getContentAsString();
        assertSameIds(first, second);
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(5);
    }

    @Test
    void aStoredInvitation_isReturned_afterARefreshDropsItsSegment() throws Exception {
        Event e = plannedEvent(28);
        String loyalBoth = seg("loyal", "same", "[\"launch\",\"d3\"]", null);
        String first = invite(e, loyalBoth).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID oldPlan = currentPlan(e);
        // The loyal guests dropped out; the recomputed plan no longer shows loyal/same.
        dropOut(loyal);
        mvc.perform(post(planUrl(e)).with(auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetPct\":90}")).andExpect(status().isOk());
        assertThat(currentPlan(e)).isNotEqualTo(oldPlan);
        assertThat(jdbc.queryForList("select class from audience_plan_segments where plan_id = ?", String.class,
                currentPlan(e))).doesNotContain("loyal");

        String second = invite(e, loyalBoth).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].created").value(false))
                .andExpect(jsonPath("$.invitations[0].classKey").value("loyal"))
                .andExpect(jsonPath("$.invitations[0].genreFit").value("same"))
                .andExpect(jsonPath("$.invitations[0].planId").value(oldPlan.toString()))
                .andExpect(jsonPath("$.invitations[0].members").value(40))
                .andReturn().getResponse().getContentAsString();
        for (int a = 0; a < 2; a++) {
            for (String f : List.of(".arm", ".experimentId", ".members", ".segmentId", ".campaignId")) {
                String path = "$.invitations[0].arms[" + a + "]" + f;
                assertThat((Object) JsonPath.read(second, path)).isEqualTo(JsonPath.read(first, path));
            }
        }

        // A deleted draft is still rebuilt from the stored assignments, labelled with the stored keys.
        String d3Campaign = JsonPath.read(first, "$.invitations[0].arms[1].campaignId");
        mvc.perform(delete("/api/v1/marketing/campaigns/" + d3Campaign).with(auth(owner)))
                .andExpect(status().isNoContent());
        String rebuilt = invite(e, loyalBoth.replaceFirst("\\{", "{\"recreateMissingDrafts\":true,"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].arms[1].draftMissing").value(false))
                .andReturn().getResponse().getContentAsString();
        String newCampaign = JsonPath.read(rebuilt, "$.invitations[0].arms[1].campaignId");
        assertThat(newCampaign).isNotEqualTo(d3Campaign);
        assertThat(jdbc.queryForObject("select name from campaigns where id = ?", String.class, UUID.fromString(newCampaign)))
                .startsWith("Audience plan · loyal/same · d3");
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(2);

        // A class × fit never invited and not on the current plan is still refused.
        invite(e, seg("loyal", "adjacent", "[\"launch\"]", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields['segments[0]']").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"segments\":[{\"genreFit\":\"same\",\"arms\":[\"launch\"]}]}",
            "{\"segments\":[{\"classKey\":\"loyal\",\"arms\":[\"launch\"]}]}",
            "{\"segments\":[{\"classKey\":\"  \",\"genreFit\":\"same\",\"arms\":[\"launch\"]}]}"})
    void aMissingOrBlankClassKeyOrFit_is400(String body) throws Exception {
        Event e = plannedEvent(28);
        invite(e, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields['segments[0]']").value("classKey and genreFit are required"));
        assertNothingWritten(e);
    }

    @Test
    void aNewSegment_isInvited_nextToAnAlreadyInvitedOne() throws Exception {
        Event e = plannedEvent(28);
        invite(e, "{\"segments\":[{\"classKey\":\"loyal\",\"genreFit\":\"same\",\"arms\":[\"launch\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].arms.length()").value(1))
                .andExpect(jsonPath("$.invitations[0].arms[0].members").value(40));

        invite(e, "{\"segments\":[{\"classKey\":\"loyal\",\"genreFit\":\"same\",\"arms\":[\"launch\"]},"
                + "{\"classKey\":\"repeat\",\"genreFit\":\"same\",\"arms\":[\"launch\"],\"holdoutPct\":20}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].created").value(false))
                .andExpect(jsonPath("$.invitations[0].arms.length()").value(1))
                .andExpect(jsonPath("$.invitations[1].created").value(true))
                .andExpect(jsonPath("$.invitations[1].holdout.members").value(14))
                .andExpect(jsonPath("$.invitations[1].arms[0].members").value(56));
    }

    @Test
    void aRepeatWithOtherArms_is409NamingTheStoredArms_andWritesNothing() throws Exception {
        Event e = plannedEvent(28);
        invite(e, seg("loyal", "same", "[\"launch\"]", null)).andExpect(status().isOk());
        int experiments = count("select count(*) from audience_experiments where event_id = ?", e.getId());
        int campaigns = count("select count(*) from campaigns where org_id = ?", orgA);

        invite(e, "{\"segments\":[{\"classKey\":\"repeat\",\"genreFit\":\"same\",\"arms\":[\"launch\"]},"
                + "{\"classKey\":\"loyal\",\"genreFit\":\"same\",\"arms\":[\"launch\",\"d3\"]}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIENCE_PLAN_ALREADY_INVITED"))
                .andExpect(jsonPath("$.error.fields['segments[1].arms']").value("launch"));
        invite(e, seg("loyal", "same", "[\"d3\"]", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fields['segments[0].arms']").value("launch"));

        // The whole call rolled back, including the new repeat segment listed first.
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(experiments);
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isEqualTo(campaigns);
    }

    @Test
    void aRepeatWithAnotherHoldoutPct_is409NamingTheStoredPct_andWritesNothing() throws Exception {
        Event e = plannedEvent(28);
        invite(e, seg("first_timer", "same", "[\"launch\"]", "15")).andExpect(status().isOk());
        // Under the holdout minimum there is no holdout row, but the requested pct is still the invitation's.
        invite(e, seg("loyal", "same", "[\"launch\"]", "20")).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].holdout").value(nullValue()));
        assertThat(jdbc.queryForList("select distinct holdout_pct from audience_experiments where event_id = ?"
                + " and plan_segment_id = ?", Integer.class, e.getId(), planSegment(currentPlan(e), "first_timer")))
                .containsExactly(15);
        int experiments = count("select count(*) from audience_experiments where event_id = ?", e.getId());
        int campaigns = count("select count(*) from campaigns where org_id = ?", orgA);

        invite(e, seg("first_timer", "same", "[\"launch\"]", "20")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIENCE_PLAN_ALREADY_INVITED"))
                .andExpect(jsonPath("$.error.fields['segments[0].holdoutPct']").value("15"))
                .andExpect(jsonPath("$.error.fields['segments[0].arms']").doesNotExist());
        invite(e, seg("loyal", "same", "[\"launch\"]", "10")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fields['segments[0].holdoutPct']").value("20"));
        invite(e, seg("first_timer", "same", "[\"d3\"]", "10")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fields['segments[0].arms']").value("launch"))
                .andExpect(jsonPath("$.error.fields['segments[0].holdoutPct']").value("15"));

        // The same pct, or none at all, returns the stored invitation.
        invite(e, seg("first_timer", "same", "[\"launch\"]", "15")).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].created").value(false))
                .andExpect(jsonPath("$.invitations[0].holdout.members").value(35));
        invite(e, seg("loyal", "same", "[\"launch\"]", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].created").value(false));
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(experiments);
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isEqualTo(campaigns);
    }

    @Test
    void aStoredD3Invitation_staysReadableAndRecreatable_afterThePlanLosesItsDMinus3Date() throws Exception {
        Event e = plannedEvent(28);
        String loyalBoth = seg("loyal", "same", "[\"launch\",\"d3\"]", null);
        String first = invite(e, loyalBoth).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String d3Campaign = JsonPath.read(first, "$.invitations[0].arms[1].campaignId");
        mvc.perform(delete("/api/v1/marketing/campaigns/" + d3Campaign).with(auth(owner)))
                .andExpect(status().isNoContent());
        // A refreshed plan closer to the event no longer offers D-3.
        jdbc.update("update audience_plans set d3_date = null where event_id = ? and superseded_by is null", e.getId());

        invite(e, loyalBoth).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].created").value(false))
                .andExpect(jsonPath("$.invitations[0].arms[1].arm").value("d3"))
                .andExpect(jsonPath("$.invitations[0].arms[1].draftMissing").value(true));
        String rebuilt = invite(e, loyalBoth.replaceFirst("\\{", "{\"recreateMissingDrafts\":true,"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].arms[1].arm").value("d3"))
                .andExpect(jsonPath("$.invitations[0].arms[1].experimentId")
                        .value((Object) JsonPath.read(first, "$.invitations[0].arms[1].experimentId")))
                .andExpect(jsonPath("$.invitations[0].arms[1].draftMissing").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(rebuilt, "$.invitations[0].arms[1].campaignId")).isNotEqualTo(d3Campaign);

        // A segment not yet invited still cannot get d3 on this plan.
        invite(e, seg("repeat", "same", "[\"d3\"]", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields['segments[0].arms']").exists());
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(2);
    }

    @Test
    void aDeletedArmDraft_isReportedMissing_andRecreatedOnRequestFromTheStoredAssignments() throws Exception {
        Event e = plannedEvent(28);
        String first = invite(e, BOTH_SEGMENTS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String experimentId = JsonPath.read(first, "$.invitations[0].arms[0].experimentId");
        String campaignId = JsonPath.read(first, "$.invitations[0].arms[0].campaignId");
        mvc.perform(delete("/api/v1/marketing/campaigns/" + campaignId).with(auth(owner)))
                .andExpect(status().isNoContent());
        int experiments = count("select count(*) from audience_experiments where event_id = ?", e.getId());

        invite(e, BOTH_SEGMENTS).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].created").value(false))
                .andExpect(jsonPath("$.invitations[0].members").value(235))
                .andExpect(jsonPath("$.invitations[0].arms[0].experimentId").value(experimentId))
                .andExpect(jsonPath("$.invitations[0].arms[0].members").value(100))
                .andExpect(jsonPath("$.invitations[0].arms[0].draftMissing").value(true))
                .andExpect(jsonPath("$.invitations[0].arms[0].campaignId").value(nullValue()))
                .andExpect(jsonPath("$.invitations[0].arms[0].segmentId").value(nullValue()))
                .andExpect(jsonPath("$.invitations[0].arms[1].draftMissing").value(false))
                .andExpect(jsonPath("$.invitations[0].arms[1].campaignId")
                        .value((Object) JsonPath.read(first, "$.invitations[0].arms[1].campaignId")));
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isEqualTo(3);

        String body = BOTH_SEGMENTS.replaceFirst("\\{", "{\"recreateMissingDrafts\":true,");
        String rebuilt = invite(e, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].created").value(false))
                .andExpect(jsonPath("$.invitations[0].arms[0].experimentId").value(experimentId))
                .andExpect(jsonPath("$.invitations[0].arms[0].draftMissing").value(false))
                .andExpect(jsonPath("$.invitations[0].arms[0].members").value(100))
                .andReturn().getResponse().getContentAsString();
        UUID newCampaign = UUID.fromString(JsonPath.read(rebuilt, "$.invitations[0].arms[0].campaignId"));
        assertThat(newCampaign).isNotEqualTo(UUID.fromString(campaignId));
        Map<String, Object> c = jdbc.queryForMap(
                "select origin, status, event_id, segment_id from campaigns where id = ?", newCampaign);
        assertThat(c.get("origin")).isEqualTo("audience_plan");
        assertThat(c.get("status")).isEqualTo("draft");
        assertThat(c.get("event_id")).isEqualTo(e.getId());
        assertThat(c.get("segment_id").toString()).isEqualTo(JsonPath.read(rebuilt, "$.invitations[0].arms[0].segmentId"));
        assertThat(jdbc.queryForObject("select campaign_id from audience_experiments where id = ?", UUID.class,
                UUID.fromString(experimentId))).isEqualTo(newCampaign);
        String snapshot = jdbc.queryForObject("select snapshot_ids from segments where id = ?", String.class,
                c.get("segment_id"));
        List<UUID> armMembers = jdbc.queryForList(
                "select membership_id from audience_assignments where experiment_id = ?", UUID.class,
                UUID.fromString(experimentId));
        assertThat(armMembers).hasSize(100);
        for (UUID m : armMembers) assertThat(snapshot).contains(m.toString());
        // Assignments stay as they were: no new experiment, no reshuffle.
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(experiments);

        invite(e, BOTH_SEGMENTS).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].arms[0].campaignId").value(newCampaign.toString()))
                .andExpect(jsonPath("$.invitations[0].arms[0].draftMissing").value(false));
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isEqualTo(4);
    }

    @Test
    void longEventNames_areTruncatedToTheCampaignAndSegmentLimits() throws Exception {
        Event e = plannedEvent(28);
        String name = "N".repeat(250);
        jdbc.update("update events set name = ? where id = ?", name, e.getId());
        String body = invite(e, seg("loyal", "same", "[\"launch\"]", null)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID campaignId = UUID.fromString(JsonPath.read(body, "$.invitations[0].arms[0].campaignId"));
        UUID segmentId = UUID.fromString(JsonPath.read(body, "$.invitations[0].arms[0].segmentId"));
        String label = "Audience plan · loyal/same · launch · " + name;
        assertThat(jdbc.queryForObject("select name from campaigns where id = ?", String.class, campaignId))
                .isEqualTo(label.substring(0, 120));
        assertThat(jdbc.queryForObject("select name from segments where id = ?", String.class, segmentId))
                .isEqualTo(label.substring(0, 128));
    }

    // ── refusals ────────────────────────────────────────────────────────────

    @Test
    void anEventThatHasStarted_is409_evenForAnInvalidBody_andWritesNothing() throws Exception {
        Event e = plannedEvent(28);
        jdbc.update("update events set starts_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), e.getId());
        invite(e, BOTH_SEGMENTS).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        invite(e, "{\"segments\":[]}").andExpect(status().isConflict());
        assertNothingWritten(e);
    }

    @Test
    void anotherOrgsEvent_is404_andWritesNothing() throws Exception {
        Event e = plannedEvent(28);
        AuthPrincipal other = new AuthPrincipal(user(orgB), orgB, UserRole.OWNER, UUID.randomUUID());
        mvc.perform(post(url(e)).with(auth(other)).contentType(MediaType.APPLICATION_JSON).content(BOTH_SEGMENTS))
                .andExpect(status().isNotFound());
        assertNothingWritten(e);
    }

    @Test
    void killSwitchOff_is404_evenForAnInvalidBody_andWritesNothing() throws Exception {
        Event e = plannedEvent(28);
        flips.set(props, "enabled", false);
        invite(e, BOTH_SEGMENTS).andExpect(status().isNotFound());
        invite(e, "{\"segments\":[]}").andExpect(status().isNotFound());
        assertNothingWritten(e);
    }

    @Test
    void anEventWithoutAPlan_is404() throws Exception {
        Event e = event(orgA, 28, 200, 100);
        populate();
        invite(e, BOTH_SEGMENTS).andExpect(status().isNotFound());
        assertNothingWritten(e);
    }

    @Test
    void invalidBodies_are400_andWriteNothing() throws Exception {
        Event e = plannedEvent(28);
        String[][] cases = {
                {"{}", "segments"},
                {"{\"segments\":[]}", "segments"},
                {seg("first_timer", "same", "[\"launch\"]", "9"), "segments[0].holdoutPct"},
                {seg("first_timer", "same", "[\"launch\"]", "21"), "segments[0].holdoutPct"},
                {seg("vip", "same", "[\"launch\"]", null), "segments[0]"},
                {seg("loyal", "adjacent", "[\"launch\"]", null), "segments[0]"},
                {seg("loyal", "same", "[]", null), "segments[0].arms"},
                {seg("loyal", "same", "[\"two_emails\"]", null), "segments[0].arms"},
                {seg("loyal", "same", "[\"launch\",\"launch\"]", null), "segments[0].arms"},
                {"{\"segments\":[{\"classKey\":\"loyal\",\"genreFit\":\"same\",\"arms\":[\"launch\"]},"
                        + "{\"classKey\":\"loyal\",\"genreFit\":\"same\",\"arms\":[\"d3\"]}]}", "segments[1]"},
        };
        for (String[] c : cases) {
            invite(e, c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                    .andExpect(jsonPath("$.error.fields['" + c[1] + "']").exists());
        }
        mvc.perform(post(url(e)).with(auth(owner))).andExpect(status().isBadRequest());
        assertNothingWritten(e);
    }

    @Test
    void boundaryHoldoutPcts_areAccepted() throws Exception {
        Event e = plannedEvent(28);
        invite(e, seg("first_timer", "same", "[\"launch\"]", "10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].holdout.members").value(23));
        invite(e, seg("repeat", "same", "[\"launch\"]", "20")).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].holdout.members").value(14));
    }

    @Test
    void d3_isRefused_whenThePlanHasNoDMinus3Date() throws Exception {
        Event e = plannedEvent(3);
        invite(e, seg("loyal", "same", "[\"d3\"]", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields['segments[0].arms']").exists());
        invite(e, seg("loyal", "same", "[\"launch\"]", null)).andExpect(status().isOk());
    }

    @Test
    void aSegmentNobodyCanBeEmailedInNow_is409_andWritesNothing() throws Exception {
        Event e = plannedEvent(28);
        // Since the plan was stored, the first-timers dropped out (e.g. unsubscribed).
        dropOut(repeat);
        dropOut(firstTimers);
        invite(e, BOTH_SEGMENTS).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertNothingWritten(e);
    }

    @Test
    void membersAreReadNow_notFromTheStoredPlan() throws Exception {
        Event e = plannedEvent(28);
        dropOut(loyal.subList(30, 40));
        dropOut(repeat);
        invite(e, seg("loyal", "same", "[\"launch\"]", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].members").value(30));
        assertThat(assigned(e)).containsExactlyInAnyOrderElementsOf(loyal.subList(0, 30));
    }

    // ── schema ──────────────────────────────────────────────────────────────

    @Test
    void aPlanWithExperiments_cannotBeDeleted_andOneArmPerPlanSegment() throws Exception {
        Event e = plannedEvent(28);
        invite(e, BOTH_SEGMENTS).andExpect(status().isOk());
        UUID planId = currentPlan(e);
        UUID segmentId = planSegment(planId, "loyal");

        assertThatThrownBy(() -> jdbc.update("delete from audience_plans where id = ?", planId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from audience_plan_segments where id = ?", segmentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into audience_experiments (id, org_id, event_id, plan_id, plan_segment_id, arm, members, seed)
                values (?, ?, ?, ?, ?, 'launch', 0, 1)""", UUID.randomUUID(), orgA, e.getId(), planId, segmentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into audience_experiments (id, org_id, event_id, plan_id, arm, members, seed)
                values (?, ?, ?, ?, 'launch', 0, 1)""", UUID.randomUUID(), orgA, e.getId(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anEventWithInvitations_canBeHardDeleted_andTakesItsExperimentsAlong() throws Exception {
        // Postgres checks the NO ACTION plan FK at statement end, after the cascade removed the experiments.
        Event e = plannedEvent(28);
        invite(e, BOTH_SEGMENTS).andExpect(status().isOk());

        jdbc.update("delete from events where id = ?", e.getId());

        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from audience_plans where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from audience_assignments a where not exists"
                + " (select 1 from audience_experiments x where x.id = a.experiment_id)")).isZero();
    }

    @Test
    void anOrgWithInvitations_canStillBeDeleted() throws Exception {
        Event e = plannedEvent(28);
        invite(e, BOTH_SEGMENTS).andExpect(status().isOk());
        assertThat(count("select count(*) from audience_experiments where org_id = ?", orgA)).isEqualTo(5);

        mvc.perform(delete("/api/v1/org").with(auth(owner))).andExpect(status().isNoContent());

        assertThat(count("select count(*) from organizations where id = ?", orgA)).isZero();
        assertThat(count("select count(*) from events where id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from audience_experiments where org_id = ?", orgA)).isZero();
        assertThat(count("select count(*) from audience_plans where org_id = ?", orgA)).isZero();
        assertThat(count("select count(*) from audience_assignments where membership_id in (select membership_id"
                + " from memberships where org_id = ?)", orgA)).isZero();
    }

    @Test
    void aPlanForAMissingOrg_isRefused_andSegmentsDefaultToOrganizer() throws Exception {
        Event e = plannedEvent(28);
        assertThatThrownBy(() -> jdbc.update("update audience_plans set org_id = ? where event_id = ?",
                UUID.randomUUID(), e.getId())).isInstanceOf(DataIntegrityViolationException.class);
        UUID segment = UUID.randomUUID();
        jdbc.update("insert into segments (id, org_id, name, kind, prebuilt, created_at, updated_at)"
                + " values (?, ?, 'Mine', 'dynamic', false, now(), now())", segment, orgA);
        assertThat(jdbc.queryForObject("select origin from segments where id = ?", String.class, segment))
                .isEqualTo("organizer");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void assertSameIds(String first, String second) {
        for (int i = 0; i < 2; i++) {
            String p = "$.invitations[" + i + "]";
            assertThat((Object) JsonPath.read(second, p + ".planSegmentId"))
                    .isEqualTo(JsonPath.read(first, p + ".planSegmentId"));
            assertThat((Object) JsonPath.read(second, p + ".members")).isEqualTo(JsonPath.read(first, p + ".members"));
            for (int a = 0; a < 2; a++) {
                String arm = p + ".arms[" + a + "]";
                for (String f : List.of(".arm", ".experimentId", ".members", ".segmentId", ".campaignId")) {
                    assertThat((Object) JsonPath.read(second, arm + f)).isEqualTo(JsonPath.read(first, arm + f));
                }
            }
        }
        assertThat((Object) JsonPath.read(second, "$.invitations[0].holdout.experimentId"))
                .isEqualTo(JsonPath.read(first, "$.invitations[0].holdout.experimentId"));
    }

    private void assertNothingWritten(Event e) {
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isZero();
        assertThat(count("select count(*) from segments where org_id = ? and origin = 'audience_plan'", orgA)).isZero();
    }

    private static String seg(String classKey, String fit, String arms, String pct) {
        return "{\"segments\":[{\"classKey\":\"" + classKey + "\",\"genreFit\":\"" + fit + "\",\"arms\":" + arms
                + (pct == null ? "" : ",\"holdoutPct\":" + pct) + "}]}";
    }

    private Set<UUID> assigned(Event e) {
        return new HashSet<>(jdbc.queryForList("""
                select a.membership_id from audience_assignments a join audience_experiments x on x.id = a.experiment_id
                 where x.event_id = ?""", UUID.class, e.getId()));
    }

    private static List<UUID> concat(List<UUID> a, List<UUID> b) {
        List<UUID> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private UUID currentPlan(Event e) {
        return jdbc.queryForObject("select id from audience_plans where event_id = ? and superseded_by is null",
                UUID.class, e.getId());
    }

    private UUID planSegment(UUID planId, String classKey) {
        return jdbc.queryForObject("select id from audience_plan_segments where plan_id = ? and class = ?",
                UUID.class, planId, classKey);
    }

    /** An event {@code days} out with the checked warm fixture and its stored plan. */
    private Event plannedEvent(int days) throws Exception {
        Event e = event(orgA, days, 200, 100);
        populate();
        mvc.perform(get(planUrl(e)).with(auth(owner))).andExpect(status().isOk());
        return e;
    }

    /** The checked warm fixture: the setUp members become plan-mailable, plus 12 legacy-unproven members. */
    private void populate() {
        if (populated) return;
        populated = true;
        PlanPopulation.makeMailable(jdbc, logic, clock, orgA, loyal, "loyal", Map.of(HOUSE, 1.0));
        PlanPopulation.makeMailable(jdbc, logic, clock, orgA, repeat, "repeat", Map.of(HOUSE, 1.0));
        PlanPopulation.makeMailable(jdbc, logic, clock, orgA, firstTimers, "first_timer", Map.of(HOUSE, 1.0));
        PlanPopulation.legacyUnproven(jdbc, clock, orgA, 12);
    }

    /** These members unsubscribed since the plan was stored, so the gate no longer lets them be emailed. */
    private void dropOut(List<UUID> members) {
        for (UUID m : members) jdbc.update("update memberships set consent_status = 'unsubscribed' where membership_id = ?", m);
    }

    private ResultActions invite(Event e, String body) throws Exception {
        return mvc.perform(post(url(e)).with(auth(owner)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String planUrl(Event e) {
        return "/api/v1/events/" + e.getId() + "/audience-plan";
    }

    private static String url(Event e) {
        return planUrl(e) + "/invitations";
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("Invite Org");
        o.setSlug("invite-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("invite@example.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    private UUID user(UUID orgId) {
        User u = new User();
        u.setEmail("invite-owner-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        return userRepo.save(u).getId();
    }

    /** The same entities as one save each, in one transaction instead of a commit per row. */
    private List<UUID> members(UUID orgId, int n) {
        return tx.execute(s -> {
            List<UUID> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Consumer c = new Consumer();
                c.setNormalizedEmail("invite-" + UUID.randomUUID() + "@example.com");
                Membership m = new Membership();
                m.setOrgId(orgId);
                m.setConsumerId(consumerRepo.save(c).getConsumerId());
                out.add(membershipRepo.save(m).getMembershipId());
            }
            return out;
        });
    }

    /** A live house night {@code days} out (20:00 Paris) with the given enabled tiers. */
    private Event event(UUID orgId, int days, int... quantities) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Invite Night");
        e.setSlug("invite-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setGenre("House & Techno");
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minusSeconds(3600));
        e.setCreatedBy(owner.userId());
        e.setCurrency("EUR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(today.plusDays(days).atTime(20, 0).atZone(PARIS).toInstant());
        e = eventRepo.save(e);
        for (int q : quantities) {
            TicketTier t = new TicketTier();
            t.setEventId(e.getId());
            t.setName("GA " + q);
            t.setPriceMinor(2000);
            t.setQuantity(q);
            t.setEnabled(true);
            tierRepo.save(t);
        }
        return e;
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
