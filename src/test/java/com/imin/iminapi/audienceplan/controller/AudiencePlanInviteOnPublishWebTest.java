package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.service.CandidateLoader;
import com.imin.iminapi.audienceplan.service.InviteOnPublishService;
import com.imin.iminapi.audienceplan.service.PlanRefreshJob;
import com.imin.iminapi.audienceplan.service.PlanService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.service.PredictorReactivityEvents;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/events/{eventId}/audience-plan/invite-on-publish} and the publish run through the refresh
 * listener's entry point, with the checked warm fixture as candidates.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AudiencePlanInviteOnPublishWebTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String HOUSE = "house & techno";
    private static final String TWO_SEGMENTS = """
            {"segments":[
              {"classKey":"loyal","genreFit":"same","arms":["launch","d3"]},
              {"classKey":"first_timer","genreFit":"same","arms":["d3","launch"],"holdoutPct":15}
            ]}""";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired TicketTierRepository tierRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired AudiencePlanProperties props;
    @Autowired PlanService planService;
    @Autowired InviteOnPublishService inviteOnPublish;
    @MockitoSpyBean CandidateLoader loader;
    @MockitoBean AuditLogger auditLogger;

    private final List<UUID> orgs = new ArrayList<>();
    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal owner;
    private LocalDate today;
    private List<UUID> loyal;
    private List<UUID> repeat;
    private List<UUID> firstTimers;

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
        props.setEnabled(true);
        for (UUID org : orgs) {
            jdbc.update("delete from audience_plan_publish_invites where org_id = ?", org);
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
            for (UUID c : consumers) jdbc.update("delete from consumers where consumer_id = ?", c);
            jdbc.update("delete from ticket_tiers where event_id in (select id from events where org_id = ?)", org);
            jdbc.update("delete from events where org_id = ?", org);
            jdbc.update("delete from users where org_id = ?", org);
            jdbc.update("delete from organizations where id = ?", org);
        }
        orgs.clear();
    }

    // ── storing the intent ─────────────────────────────────────────────────

    @Test
    void put_storesTheIntent_inCanonicalArmOrder_andGetReturnsIt() throws Exception {
        Event e = plannedDraft(28);
        mvc.perform(get(url(e)).with(auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(e.getId().toString()))
                .andExpect(jsonPath("$.inviteOnPublish").value(nullValue()));

        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(e.getId().toString()))
                .andExpect(jsonPath("$.inviteOnPublish.segments.length()").value(2))
                .andExpect(jsonPath("$.inviteOnPublish.segments[1].arms[0]").value("launch"))
                .andExpect(jsonPath("$.inviteOnPublish.segments[1].arms[1]").value("d3"))
                .andExpect(jsonPath("$.inviteOnPublish.updatedAt").exists());

        mvc.perform(get(url(e)).with(auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteOnPublish.segments[0].classKey").value("loyal"))
                .andExpect(jsonPath("$.inviteOnPublish.segments[0].genreFit").value("same"))
                .andExpect(jsonPath("$.inviteOnPublish.segments[0].holdoutPct").value(nullValue()))
                .andExpect(jsonPath("$.inviteOnPublish.segments[1].classKey").value("first_timer"))
                .andExpect(jsonPath("$.inviteOnPublish.segments[1].holdoutPct").value(15));
        assertThat(jdbc.queryForObject("select created_by from audience_plan_publish_invites where event_id = ?",
                UUID.class, e.getId())).isEqualTo(owner.userId());
        // Drafts are created on publish, not before.
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isZero();
    }

    @Test
    void aSecondPut_replacesTheSegments() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        putIntent(e, seg("repeat", "[\"launch\"]", null)).andExpect(status().isOk());

        mvc.perform(get(url(e)).with(auth(owner)))
                .andExpect(jsonPath("$.inviteOnPublish.segments.length()").value(1))
                .andExpect(jsonPath("$.inviteOnPublish.segments[0].classKey").value("repeat"));
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isEqualTo(1);
    }

    @Test
    void delete_clearsTheIntent_andIsIdempotent() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());

        mvc.perform(delete(url(e)).with(auth(owner))).andExpect(status().isNoContent());
        mvc.perform(get(url(e)).with(auth(owner))).andExpect(jsonPath("$.inviteOnPublish").value(nullValue()));
        mvc.perform(delete(url(e)).with(auth(owner))).andExpect(status().isNoContent());
    }

    // ── refusals ────────────────────────────────────────────────────────────

    @Test
    void aPublishedEvent_is409OnPutAndDelete_evenForAnInvalidBody() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        jdbc.update("update events set status = 'LIVE' where id = ?", e.getId());

        putIntent(e, TWO_SEGMENTS).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        putIntent(e, "{\"segments\":[]}").andExpect(status().isConflict());
        mvc.perform(delete(url(e)).with(auth(owner))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isEqualTo(1);
    }

    @Test
    void killSwitchOff_is404_onEveryVerb_evenForAnInvalidBody() throws Exception {
        Event e = plannedDraft(28);
        props.setEnabled(false);
        mvc.perform(get(url(e)).with(auth(owner))).andExpect(status().isNotFound());
        putIntent(e, TWO_SEGMENTS).andExpect(status().isNotFound());
        putIntent(e, "{\"segments\":[]}").andExpect(status().isNotFound());
        mvc.perform(delete(url(e)).with(auth(owner))).andExpect(status().isNotFound());
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isZero();
    }

    @Test
    void anotherOrgsEvent_is404() throws Exception {
        Event e = plannedDraft(28);
        AuthPrincipal other = new AuthPrincipal(user(orgB), orgB, UserRole.OWNER, UUID.randomUUID());
        mvc.perform(get(url(e)).with(auth(other))).andExpect(status().isNotFound());
        mvc.perform(put(url(e)).with(auth(other)).contentType(MediaType.APPLICATION_JSON).content(TWO_SEGMENTS))
                .andExpect(status().isNotFound());
        mvc.perform(delete(url(e)).with(auth(other))).andExpect(status().isNotFound());
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isZero();
    }

    @Test
    void aDraftWithoutAPlan_is404OnPut() throws Exception {
        Event e = draft(28);
        stub(e, fixture());
        putIntent(e, TWO_SEGMENTS).andExpect(status().isNotFound());
    }

    @Test
    void invalidBodies_are400_andStoreNothing() throws Exception {
        Event e = plannedDraft(28);
        String[][] cases = {
                {"{}", "segments"},
                {"{\"segments\":[]}", "segments"},
                {"{\"segments\":[null]}", "segments[0]"},
                {seg("first_timer", "[\"launch\"]", "9"), "segments[0].holdoutPct"},
                {seg("first_timer", "[\"launch\"]", "21"), "segments[0].holdoutPct"},
                {seg("vip", "[\"launch\"]", null), "segments[0]"},
                {seg("loyal", "[]", null), "segments[0].arms"},
                {seg("loyal", "[\"two_emails\"]", null), "segments[0].arms"},
                {seg("loyal", "[\"launch\",\"launch\"]", null), "segments[0].arms"},
                {"{\"segments\":[{\"classKey\":\"loyal\",\"genreFit\":\"same\",\"arms\":[\"launch\"]},"
                        + "{\"classKey\":\"loyal\",\"genreFit\":\"same\",\"arms\":[\"d3\"]}]}", "segments[1]"},
        };
        for (String[] c : cases) {
            putIntent(e, c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                    .andExpect(jsonPath("$.error.fields['" + c[1] + "']").exists());
        }
        mvc.perform(put(url(e)).with(auth(owner))).andExpect(status().isBadRequest());
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isZero();
    }

    // ── on publish ──────────────────────────────────────────────────────────

    @Test
    void aStoredIntent_runsOnceOnPublish_andCreatesDraftsThatCannotBeSent() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());

        publish(e);

        // loyal (40): no holdout, 20 + 20; first-timers (235): 35 held out, 100 + 100.
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(5);
        assertThat(count("select count(*) from audience_experiments where event_id = ? and arm = 'holdout'", e.getId()))
                .isEqualTo(1);
        assertThat(count("select count(*) from campaigns where event_id = ?", e.getId())).isEqualTo(4);
        for (Map<String, Object> c : jdbc.queryForList(
                "select id, status, origin, scheduled_at, created_by from campaigns where event_id = ?", e.getId())) {
            assertThat(c.get("status")).isEqualTo("draft");
            assertThat(c.get("origin")).isEqualTo("audience_plan");
            assertThat(c.get("scheduled_at")).isNull();
            assertThat(c.get("created_by")).isEqualTo(owner.userId());
        }
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isZero();

        UUID campaignId = jdbc.queryForObject("select id from campaigns where event_id = ? limit 1", UUID.class, e.getId());
        mvc.perform(post("/api/v1/marketing/campaigns/" + campaignId + "/send").with(auth(owner))
                        .header("Idempotency-Key", "idem-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIENCE_SENDS_DISABLED"));

        // A second publish run (e.g. unpublish and publish again) finds no intent and writes nothing.
        publish(e);
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isEqualTo(5);
        assertThat(count("select count(*) from campaigns where event_id = ?", e.getId())).isEqualTo(4);
    }

    @Test
    void aClearedIntent_doesNothingOnPublish() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        mvc.perform(delete(url(e)).with(auth(owner))).andExpect(status().isNoContent());

        publish(e);

        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from campaigns where org_id = ?", orgA)).isZero();
    }

    @Test
    void aSegmentTheRefreshedPlanNoLongerShows_isSkipped_andTheOtherIsStillInvited() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        // Before the publish, the loyal guests dropped out; the refresh writes a plan without them.
        List<Person> now = new ArrayList<>(people("repeat", repeat));
        now.addAll(people("first_timer", firstTimers));
        stub(e, now);

        publish(e);

        assertThat(jdbc.queryForList("""
                select s.class from audience_plan_segments s join audience_plans p on p.id = s.plan_id
                 where p.event_id = ? and p.superseded_by is null""", String.class, e.getId())).doesNotContain("loyal");
        assertThat(jdbc.queryForList("""
                select distinct s.class from audience_experiments x join audience_plan_segments s on s.id = x.plan_segment_id
                 where x.event_id = ?""", String.class, e.getId())).containsExactly("first_timer");
        assertThat(count("select count(*) from campaigns where event_id = ?", e.getId())).isEqualTo(2);
    }

    @Test
    void aRefusedSegment_isLogged_andTheOthersAreStillInvited() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        // Invited by hand with other arms first, so the stored loyal/same invitation answers 409 on publish.
        mvc.perform(post("/api/v1/events/" + e.getId() + "/audience-plan/invitations").with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(seg("loyal", "[\"launch\"]", null)))
                .andExpect(status().isOk());

        publish(e);

        assertThat(count("select count(*) from audience_experiments where event_id = ? and arm = 'd3'", e.getId()))
                .isEqualTo(1);
        assertThat(count("""
                select count(*) from audience_experiments x join audience_plan_segments s on s.id = x.plan_segment_id
                 where x.event_id = ? and s.class = 'first_timer'""", e.getId())).isEqualTo(3);
        assertThat(count("""
                select count(*) from audience_experiments x join audience_plan_segments s on s.id = x.plan_segment_id
                 where x.event_id = ? and s.class = 'loyal'""", e.getId())).isEqualTo(1);
    }

    @Test
    void d3_isDroppedOnPublish_whenTheRefreshedPlanHasNoDMinus3Date() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        jdbc.update("update events set starts_at = ? where id = ?",
                Timestamp.from(today.plusDays(3).atTime(20, 0).atZone(PARIS).toInstant()), e.getId());

        publish(e);

        assertThat(jdbc.queryForList("select distinct arm from audience_experiments where event_id = ? and arm <> 'holdout'",
                String.class, e.getId())).containsExactly("launch");
        assertThat(count("select count(*) from campaigns where event_id = ?", e.getId())).isEqualTo(2);
    }

    @Test
    void aSegmentWhoseOnlyArmIsD3_isSkippedOnPublish_whenThePlanHasNoDMinus3Date(CapturedOutput output)
            throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, """
                {"segments":[
                  {"classKey":"loyal","genreFit":"same","arms":["d3"]},
                  {"classKey":"first_timer","genreFit":"same","arms":["launch"]}
                ]}""").andExpect(status().isOk());
        jdbc.update("update events set starts_at = ? where id = ?",
                Timestamp.from(today.plusDays(3).atTime(20, 0).atZone(PARIS).toInstant()), e.getId());

        publish(e);

        assertThat(jdbc.queryForList("""
                select distinct s.class from audience_experiments x join audience_plan_segments s on s.id = x.plan_segment_id
                 where x.event_id = ?""", String.class, e.getId())).containsExactly("first_timer");
        assertThat(count("select count(*) from campaigns where event_id = ?", e.getId())).isEqualTo(1);
        assertThat(output.getOut()).contains("segment loyal/same has no arm left without D-3; skipped");
    }

    @Test
    void aSoftDeletedEvent_writesNothingOnPublish_andDropsTheIntent(CapturedOutput output) throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        jdbc.update("update events set deleted_at = ? where id = ?", Timestamp.from(Instant.now()), e.getId());

        publish(e);

        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from campaigns where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isZero();
        assertThat(output.getOut()).contains("InviteOnPublish: event " + e.getId() + " gone or its org is off");
    }

    @Test
    void anEventNowInAnotherOrg_writesNothingOnPublish_andDropsTheIntent(CapturedOutput output) throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        jdbc.update("update events set org_id = ?, created_by = ? where id = ?", orgB, user(orgB), e.getId());

        publish(e);

        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from campaigns where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isZero();
        assertThat(output.getOut()).contains("InviteOnPublish: event " + e.getId() + " gone or its org is off");
    }

    @Test
    void noCurrentPlanAtPublish_writesNothing_andDropsTheIntent(CapturedOutput output) throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        jdbc.update("delete from audience_plan_segments where plan_id in (select id from audience_plans where event_id = ?)", e.getId());
        jdbc.update("update audience_plans set superseded_by = null where event_id = ?", e.getId());
        jdbc.update("delete from audience_plans where event_id = ?", e.getId());
        jdbc.update("update events set status = 'LIVE' where id = ?", e.getId());

        // Called without the refresh, so no plan is recomputed first.
        inviteOnPublish.runOnPublish(e.getId());

        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from campaigns where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isZero();
        assertThat(output.getOut()).contains("InviteOnPublish: event " + e.getId() + " has no plan; nothing invited");
    }

    @Test
    void killSwitchOffAtPublish_writesNothing_andDropsTheIntent() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        props.setEnabled(false);

        publish(e);

        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isZero();
        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isZero();
    }

    @Test
    void anIntentWhoseCreatorWasDeleted_stillRunsWithoutAnActor() throws Exception {
        Event e = plannedDraft(28);
        AuthPrincipal member = new AuthPrincipal(user(orgA), orgA, UserRole.MEMBER, UUID.randomUUID());
        mvc.perform(put(url(e)).with(auth(member)).contentType(MediaType.APPLICATION_JSON)
                .content(seg("loyal", "[\"launch\"]", null))).andExpect(status().isOk());
        jdbc.update("delete from users where id = ?", member.userId());
        assertThat(jdbc.queryForObject("select created_by from audience_plan_publish_invites where event_id = ?",
                UUID.class, e.getId())).isNull();

        publish(e);

        assertThat(count("select count(*) from campaigns where event_id = ? and created_by is null", e.getId())).isEqualTo(1);
    }

    @Test
    void anEventDelete_removesItsIntent() throws Exception {
        Event e = plannedDraft(28);
        putIntent(e, TWO_SEGMENTS).andExpect(status().isOk());
        jdbc.update("delete from audience_plan_segments where plan_id in (select id from audience_plans where event_id = ?)", e.getId());
        jdbc.update("update audience_plans set superseded_by = null where event_id = ?", e.getId());
        jdbc.update("delete from audience_plans where event_id = ?", e.getId());
        jdbc.update("delete from ticket_tiers where event_id = ?", e.getId());
        jdbc.update("delete from events where id = ?", e.getId());

        assertThat(count("select count(*) from audience_plan_publish_invites where event_id = ?", e.getId())).isZero();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** The publish commit: the event goes live and the refresh listener runs, here on the test thread. */
    private void publish(Event e) {
        jdbc.update("update events set status = 'LIVE', published_at = ? where id = ?",
                Timestamp.from(Instant.now()), e.getId());
        new PlanRefreshJob(planService, eventRepo, Clock.systemUTC(), null, inviteOnPublish)
                .onEventPublished(new PredictorReactivityEvents.EventPublished(e.getId()));
    }

    private static String seg(String classKey, String arms, String pct) {
        return "{\"segments\":[{\"classKey\":\"" + classKey + "\",\"genreFit\":\"same\",\"arms\":" + arms
                + (pct == null ? "" : ",\"holdoutPct\":" + pct) + "}]}";
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** A draft {@code days} out with the checked warm fixture and its stored plan. */
    private Event plannedDraft(int days) throws Exception {
        Event e = draft(days);
        stub(e, fixture());
        mvc.perform(get("/api/v1/events/" + e.getId() + "/audience-plan").with(auth(owner))).andExpect(status().isOk());
        return e;
    }

    private List<Person> fixture() {
        List<Person> all = new ArrayList<>();
        all.addAll(people("loyal", loyal));
        all.addAll(people("repeat", repeat));
        all.addAll(people("first_timer", firstTimers));
        return all;
    }

    private static List<Person> people(String classKey, List<UUID> ids) {
        List<Person> out = new ArrayList<>();
        for (UUID id : ids) out.add(new Person(id, classKey, Map.of(HOUSE, 1.0), 0, false, false, 0, 0));
        return out;
    }

    private void stub(Event e, List<Person> people) {
        Map<String, Integer> gate = new LinkedHashMap<>();
        gate.put("legacy_unproven", 12);
        doReturn(new CandidateBuilder.Input(e.getOrgId(), e.getGenreKey(), 255, 1.6, gate, people))
                .when(loader).input(eq(e.getOrgId()), any(), anyInt(), anyDouble());
    }

    private ResultActions putIntent(Event e, String body) throws Exception {
        return mvc.perform(put(url(e)).with(auth(owner)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String url(Event e) {
        return "/api/v1/events/" + e.getId() + "/audience-plan/invite-on-publish";
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("Publish Invite Org");
        o.setSlug("pub-invite-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("pub-invite@example.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    private UUID user(UUID orgId) {
        User u = new User();
        u.setEmail("pub-invite-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        return userRepo.save(u).getId();
    }

    private List<UUID> members(UUID orgId, int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Consumer c = new Consumer();
            c.setNormalizedEmail("pub-invite-" + UUID.randomUUID() + "@example.com");
            Membership m = new Membership();
            m.setOrgId(orgId);
            m.setConsumerId(consumerRepo.save(c).getConsumerId());
            out.add(membershipRepo.save(m).getMembershipId());
        }
        return out;
    }

    /** A draft house night {@code days} out (20:00 Paris), 300 capacity. */
    private Event draft(int days) {
        Event e = new Event();
        e.setOrgId(orgA);
        e.setName("Publish Invite Night");
        e.setSlug("pub-invite-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setGenre("House & Techno");
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCreatedBy(owner.userId());
        e.setCurrency("EUR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(today.plusDays(days).atTime(20, 0).atZone(PARIS).toInstant());
        e = eventRepo.save(e);
        for (int q : new int[] {200, 100}) {
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
