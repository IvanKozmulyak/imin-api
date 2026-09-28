package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.engine.ArmTimes;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.service.CandidateLoader;
import com.imin.iminapi.audienceplan.service.TimingArmScheduler;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.RecipientMaterializer;
import com.imin.iminapi.marketing.service.MomentumTriggered;
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
import com.imin.iminapi.service.audit.AuditLogger;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Timing arms on the H2 test profile: approving an arm draft through {@code POST /marketing/campaigns/{id}/send}
 * stores the arm's own send time, a slump arm waits for Momentum's SLUMP, and the sends switch still comes first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class TimingArmSchedulingTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String HOUSE = "house & techno";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired TicketTierRepository tierRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired CampaignRepository campaignRepo;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired RecipientMaterializer materializer;
    @Autowired AudiencePlanProperties props;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TimingArmScheduler scheduler;
    @MockitoSpyBean CandidateLoader loader;
    @MockitoBean AuditLogger auditLogger;

    private final List<UUID> orgs = new ArrayList<>();
    private UUID orgA;
    private AuthPrincipal owner;
    private LocalDate today;
    private List<UUID> loyal;

    @BeforeEach
    void setUp() {
        orgA = org();
        owner = new AuthPrincipal(user(orgA), orgA, UserRole.OWNER, UUID.randomUUID());
        today = LocalDate.now(PARIS);
        loyal = members(orgA, 60);
        props.setSendsEnabled(true);
    }

    @AfterEach
    void tearDown() {
        props.setSendsEnabled(false);
        props.setEnabled(true);
        for (UUID org : orgs) {
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

    // ── approval sets the arm's time ─────────────────────────────────────────

    @Test
    void approvingAD3Arm_schedulesIt3DaysBeforeAt18EventTime() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"d3\"]"), "d3");

        String body = approve(campaign, null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.armed").value(false)).andReturn().getResponse().getContentAsString();

        assertThat(campaignStatus(campaign)).isEqualTo("scheduled");
        Instant expected = today.plusDays(25).atTime(18, 0).atZone(PARIS).toInstant();
        assertThat(scheduledAt(campaign)).isEqualTo(expected);
        assertThat(Instant.parse(JsonPath.read(body, "$.scheduledAt"))).isEqualTo(expected);
    }

    @Test
    void approvingALaunchArm_waitsForTheOnSaleDate() throws Exception {
        Instant onSale = today.plusDays(2).atTime(12, 0).atZone(PARIS).toInstant();
        Event e = plannedEvent(28, onSale);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"d3\"]"), "launch");

        approve(campaign, null).andExpect(status().isAccepted());

        assertThat(scheduledAt(campaign)).isEqualTo(onSale);
    }

    @Test
    void approvingALaunchArm_alreadyOnSale_schedulesItNowOutsideQuietHours() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"d3\"]"), "launch");

        Instant before = Instant.now();
        approve(campaign, null).andExpect(status().isAccepted());
        Instant after = Instant.now();

        assertThat(scheduledAt(campaign)).isBetween(ArmTimes.outOfQuietHours(before, PARIS).minusMillis(1),
                ArmTimes.outOfQuietHours(after, PARIS).plusMillis(1));
    }

    @Test
    void approvingAnEarlyBirdArm_schedulesIt18OnTheDayTheCheapTierCloses() throws Exception {
        Event e = plannedEvent(28, null);
        earlyBird(e, today.plusDays(10).atTime(23, 30).atZone(PARIS).toInstant());
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"early_bird_end\"]"), "early_bird_end");

        approve(campaign, null).andExpect(status().isAccepted());

        assertThat(scheduledAt(campaign)).isEqualTo(today.plusDays(10).atTime(18, 0).atZone(PARIS).toInstant());
    }

    @Test
    void anEarlyBirdArmThatNoLongerApplies_isRefusedAtApproval() throws Exception {
        Event e = plannedEvent(28, null);
        earlyBird(e, today.plusDays(10).atTime(23, 30).atZone(PARIS).toInstant());
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"early_bird_end\"]"), "early_bird_end");
        jdbc.update("update ticket_tiers set sale_closes_at = null where event_id = ?", e.getId());

        approve(campaign, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void anEarlyBirdArm_isRefusedAtInvitation_whenNoTierEndsBeforeD3() throws Exception {
        Event e = plannedEvent(28, null);

        inviteCall(e, "[\"launch\",\"early_bird_end\"]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields['segments[0].arms']").exists());
        assertThat(count("select count(*) from audience_experiments where event_id = ?", e.getId())).isZero();
    }

    @Test
    void aClientTime_isRefusedForAnArm() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"d3\"]"), "d3");

        approve(campaign, today.plusDays(5).atTime(12, 0).atZone(PARIS).toInstant())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.scheduledAt").exists());
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void theSendsSwitch_stillAnswersFirst() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"slump\"]"), "slump");
        props.setSendsEnabled(false);

        approve(campaign, Instant.now().plusSeconds(3600)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIENCE_SENDS_DISABLED"));
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
        assertThat(armedAt(campaign)).isNull();
    }

    @Test
    void aD3ArmWhoseTimeHasPassed_isRefused() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"d3\"]"), "d3");
        // The event moved to the day after tomorrow: its D-3 evening is already behind us.
        jdbc.update("update events set starts_at = ? where id = ?",
                Timestamp.from(today.plusDays(2).atTime(20, 0).atZone(PARIS).toInstant()), e.getId());

        approve(campaign, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void anEarlyBirdArm_isInvited_whenTheCheapTierEndsBeforeD3() throws Exception {
        Event e = plannedEvent(28, null);
        earlyBird(e, today.plusDays(10).atTime(23, 30).atZone(PARIS).toInstant());

        String body = invite(e, "[\"launch\",\"early_bird_end\"]");

        List<String> arms = JsonPath.read(body, "$.invitations[0].arms[*].arm");
        assertThat(arms).containsExactlyInAnyOrder("launch", "early_bird_end");
        UUID campaign = armCampaign(body, "early_bird_end");
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
        assertThat(jdbc.queryForObject("select arm from audience_experiments where campaign_id = ?", String.class,
                campaign)).isEqualTo("early_bird_end");
    }

    @Test
    void anArmOfADeletedEvent_isRefusedAtApproval() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"d3\"]"), "d3");
        jdbc.update("update events set deleted_at = ? where id = ?", Timestamp.from(Instant.now()), e.getId());

        approve(campaign, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message").value("The invited event no longer exists"));
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void anArmOfAnEventThatHasStarted_isRefusedAtApproval() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"d3\"]"), "launch");
        startsAt(e, Instant.now().minusSeconds(60));

        approve(campaign, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message").value("The event has already started"));
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void anArmPushedByQuietHoursPastTheEventStart_isRefused() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"d3\"]"), "launch");
        // The org is at ~03:00 local, so "now" moves to 09:00, after an event starting in an hour.
        orgTimezone(quietZoneNow());
        startsAt(e, Instant.now().plusSeconds(3600));

        approve(campaign, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message").value("This arm would go out after the event starts"));
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void anAudiencePlanCampaignThatIsNotAnArm_keepsTheRequestedTime() throws Exception {
        UUID campaign = campaign("draft", "audience_plan", null).getId();
        Instant requested = today.plusDays(5).atTime(12, 0).atZone(PARIS).toInstant();

        String body = approve(campaign, requested).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.armed").value(false)).andReturn().getResponse().getContentAsString();

        assertThat(scheduledAt(campaign)).isEqualTo(requested);
        assertThat(Instant.parse(JsonPath.read(body, "$.scheduledAt"))).isEqualTo(requested);
    }

    // ── slump ───────────────────────────────────────────────────────────────

    @Test
    void approvingASlumpArm_armsItWithoutScheduling_andASecondApprovalIsRefused() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"slump\"]"), "slump");

        approve(campaign, null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.armed").value(true))
                .andExpect(jsonPath("$.scheduledAt").doesNotExist());

        assertThat(campaignStatus(campaign)).isEqualTo("draft");
        assertThat(scheduledAt(campaign)).isNull();
        assertThat(armedAt(campaign)).isNotNull();
        approve(campaign, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void aSlumpTrigger_schedulesTheArmedSlumpDraft() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armedSlump(e);

        Instant before = Instant.now();
        publisher.publishEvent(new MomentumTriggered(orgA, e.getId(), "slump"));
        Instant after = Instant.now();

        assertThat(campaignStatus(campaign)).isEqualTo("scheduled");
        assertThat(scheduledAt(campaign)).isBetween(ArmTimes.outOfQuietHours(before, PARIS).minusMillis(1),
                ArmTimes.outOfQuietHours(after, PARIS).plusMillis(1));
    }

    @Test
    void anotherTrigger_leavesTheSlumpDraftAlone() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armedSlump(e);

        publisher.publishEvent(new MomentumTriggered(orgA, e.getId(), "urgency_72h"));

        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void aSlumpDraftNobodyApproved_isNotScheduled() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"slump\"]"), "slump");

        publisher.publishEvent(new MomentumTriggered(orgA, e.getId(), "slump"));

        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void aSlumpWhileSendsAreOff_schedulesNothing() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armedSlump(e);
        props.setSendsEnabled(false);

        publisher.publishEvent(new MomentumTriggered(orgA, e.getId(), "slump"));

        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void aSlumpForAnOrgOffTheBeta_schedulesNothing() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armedSlump(e);
        props.setEnabled(false);

        publisher.publishEvent(new MomentumTriggered(orgA, e.getId(), "slump"));

        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void aSlumpForAnOrgWithoutItsLegalIdentity_schedulesNothing() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armedSlump(e);
        jdbc.update("update organizations set legal_name = null where id = ?", orgA);

        publisher.publishEvent(new MomentumTriggered(orgA, e.getId(), "slump"));

        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void aSlumpAfterTheEventStarted_schedulesNothing() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armedSlump(e);
        jdbc.update("update events set starts_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(60)),
                e.getId());

        publisher.publishEvent(new MomentumTriggered(orgA, e.getId(), "slump"));

        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void approvingASlumpArmThatIsNotADraft_isRefused() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"slump\"]"), "slump");
        jdbc.update("update campaigns set status = 'scheduled', scheduled_at = ? where id = ?",
                Timestamp.from(Instant.now().plusSeconds(10 * 86400)), campaign);

        approve(campaign, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message").value("Campaign is not in draft"));
        assertThat(armedAt(campaign)).isNull();
    }

    @Test
    void editingAnArmedSlumpDraft_disarmsIt_untilApprovedAgain() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armedSlump(e);

        mvc.perform(patch("/api/v1/marketing/campaigns/" + campaign).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"Edited\"}"))
                .andExpect(status().isOk());

        assertThat(armedAt(campaign)).isNull();
        publisher.publishEvent(new MomentumTriggered(orgA, e.getId(), "slump"));
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
        approve(campaign, null).andExpect(status().isAccepted()).andExpect(jsonPath("$.armed").value(true));
        assertThat(armedAt(campaign)).isNotNull();
    }

    @Test
    void aSlumpWhoseQuietHoursPushPassesTheEventStart_schedulesNothing() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armedSlump(e);
        orgTimezone(quietZoneNow());
        startsAt(e, Instant.now().plusSeconds(3600));

        assertThat(scheduler.fireSlump(orgA, e.getId())).isZero();
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    @Test
    void anArmedSlumpWithoutACampaign_isSkipped() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armedSlump(e);
        jdbc.update("update audience_experiments set campaign_id = null where campaign_id = ?", campaign);

        assertThat(scheduler.fireSlump(orgA, e.getId())).isZero();
        assertThat(campaignStatus(campaign)).isEqualTo("draft");
    }

    // ── the per-event cap still holds ───────────────────────────────────────

    @Test
    void aMemberWithTwoEmailsAboutTheEvent_isSkippedByAnArm_neverGettingAThird() throws Exception {
        Event e = plannedEvent(28, null);
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"d3\"]"), "launch");
        UUID member = jdbc.queryForObject("""
                select a.membership_id from audience_assignments a join audience_experiments x on x.id = a.experiment_id
                 where x.campaign_id = ? order by a.membership_id limit 1""", UUID.class, campaign);
        // SendGate admits this member, so only the per-event cap can hold them back.
        jdbc.update("update memberships set consent_basis = 'explicit' where membership_id = ?", member);
        sentAboutEvent(e, member);
        sentAboutEvent(e, member);

        materializer.materialize(campaignRepo.findById(campaign).orElseThrow());

        assertThat(jdbc.queryForObject(
                "select skip_reason from campaign_recipients where campaign_id = ? and membership_id = ?",
                String.class, campaign, member)).isEqualTo("event_cap");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID armedSlump(Event e) throws Exception {
        UUID campaign = armCampaign(invite(e, "[\"launch\",\"slump\"]"), "slump");
        approve(campaign, null).andExpect(status().isAccepted());
        return campaign;
    }

    private void startsAt(Event e, Instant at) {
        jdbc.update("update events set starts_at = ? where id = ?", Timestamp.from(at), e.getId());
    }

    private void orgTimezone(ZoneOffset zone) {
        jdbc.update("update organizations set timezone = ? where id = ?", zone.getId(), orgA);
    }

    /** A fixed offset whose local time is 03:00–03:59 right now, i.e. inside email quiet hours. */
    private static ZoneOffset quietZoneNow() {
        int h = Math.floorMod(3 - OffsetDateTime.now(ZoneOffset.UTC).getHour(), 24);
        return ZoneOffset.ofHours(h > 12 ? h - 24 : h);
    }

    private void sentAboutEvent(Event e, UUID member) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(campaign("sent", "manual", e.getId()).getId());
        r.setMembershipId(member);
        r.setEmail("x@example.com");
        r.setStatus("sent");
        r.setLastEventAt(Instant.now());
        recipientRepo.save(r);
    }

    private Campaign campaign(String status, String origin, UUID eventId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgA);
        c.setChannel("email");
        c.setName("Earlier");
        c.setStatus(status);
        c.setOrigin(origin);
        c.setEventId(eventId);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaignRepo.save(c);
    }

    private void earlyBird(Event e, Instant closes) {
        List<UUID> tiers = jdbc.queryForList("select id from ticket_tiers where event_id = ? order by quantity desc",
                UUID.class, e.getId());
        // The cheaper tier closes early; the other one keeps selling.
        jdbc.update("update ticket_tiers set price_minor = 1500, sale_closes_at = ? where id = ?",
                Timestamp.from(closes), tiers.get(0));
    }

    private String invite(Event e, String arms) throws Exception {
        return inviteCall(e, arms).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions inviteCall(Event e, String arms) throws Exception {
        String body = "{\"segments\":[{\"classKey\":\"loyal\",\"genreFit\":\"same\",\"arms\":" + arms + "}]}";
        return mvc.perform(post("/api/v1/events/" + e.getId() + "/audience-plan/invitations").with(auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static UUID armCampaign(String inviteBody, String arm) {
        List<String> ids = JsonPath.read(inviteBody, "$.invitations[0].arms[?(@.arm == '" + arm + "')].campaignId");
        return UUID.fromString(ids.get(0));
    }

    private ResultActions approve(UUID campaign, Instant scheduledAt) throws Exception {
        String body = scheduledAt == null ? "{}" : "{\"scheduledAt\":\"" + scheduledAt + "\"}";
        return mvc.perform(post("/api/v1/marketing/campaigns/" + campaign + "/send").with(auth(owner))
                .header("Idempotency-Key", "idem-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String campaignStatus(UUID campaign) {
        return jdbc.queryForObject("select status from campaigns where id = ?", String.class, campaign);
    }

    private Instant scheduledAt(UUID campaign) {
        OffsetDateTime at = jdbc.queryForObject("select scheduled_at from campaigns where id = ?",
                OffsetDateTime.class, campaign);
        return at == null ? null : at.toInstant();
    }

    private Instant armedAt(UUID campaign) {
        OffsetDateTime at = jdbc.queryForObject("select armed_at from audience_experiments where campaign_id = ?",
                OffsetDateTime.class, campaign);
        return at == null ? null : at.toInstant();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** An event {@code days} out with 60 loyal members (warm) stubbed as candidates and its stored plan. */
    private Event plannedEvent(int days, Instant onSaleAt) throws Exception {
        Event e = event(orgA, days, onSaleAt, 200, 100);
        List<Person> people = new ArrayList<>();
        for (UUID id : loyal) people.add(new Person(id, "loyal", Map.of(HOUSE, 1.0), 0, false, false, 0, 0));
        Map<String, Integer> gate = new LinkedHashMap<>();
        doReturn(new CandidateBuilder.Input(e.getOrgId(), e.getGenreKey(), 255, 1.6, gate, people))
                .when(loader).input(eq(e.getOrgId()), any(), anyInt(), anyDouble());
        mvc.perform(get("/api/v1/events/" + e.getId() + "/audience-plan").with(auth(owner)))
                .andExpect(status().isOk());
        return e;
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("Timing Org");
        o.setSlug("timing-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("timing@example.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        o.setLegalName("Timing Org SAS");
        o.setLegalContact("legal@example.com");
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    private UUID user(UUID orgId) {
        User u = new User();
        u.setEmail("timing-owner-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        return userRepo.save(u).getId();
    }

    private List<UUID> members(UUID orgId, int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Consumer c = new Consumer();
            c.setNormalizedEmail("timing-" + UUID.randomUUID() + "@example.com");
            Membership m = new Membership();
            m.setOrgId(orgId);
            m.setConsumerId(consumerRepo.save(c).getConsumerId());
            out.add(membershipRepo.save(m).getMembershipId());
        }
        return out;
    }

    /** A live house night {@code days} out (20:00 Paris) with the given enabled tiers. */
    private Event event(UUID orgId, int days, Instant onSaleAt, int... quantities) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Timing Night");
        e.setSlug("timing-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setGenre("House & Techno");
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minusSeconds(3600));
        e.setCreatedBy(owner.userId());
        e.setCurrency("EUR");
        e.setTimezone("Europe/Paris");
        e.setOnSaleAt(onSaleAt);
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
