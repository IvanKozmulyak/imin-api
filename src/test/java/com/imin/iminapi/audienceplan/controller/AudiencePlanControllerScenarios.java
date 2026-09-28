package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.repository.AudiencePlanRepository;
import com.imin.iminapi.audienceplan.service.CandidateLoader;
import com.imin.iminapi.config.TestRateLimitConfig;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET/POST /api/v1/events/{eventId}/audience-plan} through the web layer, on H2 and on Postgres 17. Candidate
 * people are stubbed with the checked warm fixture where the numbers matter; one test runs the real SQL loader.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
abstract class AudiencePlanControllerScenarios {

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
    @Autowired AudiencePlanProperties props;
    @Autowired AudiencePlanRepository planRepo;
    @Autowired TransactionTemplate tx;
    @MockitoSpyBean CandidateLoader loader;
    @MockitoBean AuditLogger auditLogger;

    private final List<UUID> orgs = new ArrayList<>();
    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal owner;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        orgA = org();
        orgB = org();
        owner = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.MEMBER, UUID.randomUUID());
        today = LocalDate.now(PARIS);
    }

    @AfterEach
    void tearDown() {
        props.setEnabled(true);
        for (UUID org : orgs) {
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

    // ── checked warm fixture through the API ─────────────────────────────────────

    @Test
    void get_warmFixture_returnsEveryCheckedNumber() throws Exception {
        Event e = event(orgA, 200, 100);
        stub(e, fixture());

        getPlan(e, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(e.getId().toString()))
                .andExpect(jsonPath("$.mode").value("warm"))
                .andExpect(jsonPath("$.capacity").value(300))
                .andExpect(jsonPath("$.targetTickets").value(255))
                .andExpect(jsonPath("$.mailable").value(345))
                .andExpect(jsonPath("$.expected.low").value(26))
                .andExpect(jsonPath("$.expected.mid").value(52))
                .andExpect(jsonPath("$.expected.high").value(93))
                .andExpect(jsonPath("$.coverage.low").value(0.10))
                .andExpect(jsonPath("$.coverage.mid").value(0.20))
                .andExpect(jsonPath("$.coverage.high").value(0.36))
                .andExpect(jsonPath("$.coverage.verdict").value("medium"))
                .andExpect(jsonPath("$.gap.low").value(162))
                .andExpect(jsonPath("$.gap.high").value(229))
                .andExpect(jsonPath("$.reachNeeded.metaAds.status").value("unverified"))
                .andExpect(jsonPath("$.reachNeeded.metaAds.low").value(nullValue()))
                .andExpect(jsonPath("$.reachNeeded.metaAds.high").value(nullValue()))
                .andExpect(jsonPath("$.reachNeeded.instagramOrganic.status").value("unknown"))
                .andExpect(jsonPath("$.gapExceedsTribe").value(nullValue()))
                .andExpect(jsonPath("$.segments.length()").value(3))
                .andExpect(jsonPath("$.segments[1].classKey").value("loyal"))
                .andExpect(jsonPath("$.segments[1].genreFit").value("same"))
                .andExpect(jsonPath("$.segments[1].mailable").value(40))
                .andExpect(jsonPath("$.segments[1].rate.low").value(0.12))
                .andExpect(jsonPath("$.segments[1].rate.mid").value(0.25))
                .andExpect(jsonPath("$.segments[1].rate.high").value(0.40))
                .andExpect(jsonPath("$.segments[1].ticketsPerOrder").value(1.6))
                .andExpect(jsonPath("$.segments[1].expected.low").value(8))
                .andExpect(jsonPath("$.segments[1].expected.mid").value(16))
                .andExpect(jsonPath("$.segments[1].expected.high").value(26))
                .andExpect(jsonPath("$.segments[1].confidence").value("prior"))
                .andExpect(jsonPath("$.segments[1].reason.paidOrdersMin").value(3))
                .andExpect(jsonPath("$.segments[1].reason.paidOrdersMax").value(nullValue()))
                .andExpect(jsonPath("$.segments[1].reason.daysSinceLastPaidMax").value(90))
                .andExpect(jsonPath("$.segments[1].reason.eventGenre").value(HOUSE))
                .andExpect(jsonPath("$.segments[1].reason.genreFit").value("same"))
                .andExpect(jsonPath("$.segments[2].classKey").value("repeat"))
                .andExpect(jsonPath("$.segments[2].expected.low").value(7))
                .andExpect(jsonPath("$.segments[2].expected.mid").value(13))
                .andExpect(jsonPath("$.segments[2].expected.high").value(22))
                .andExpect(jsonPath("$.segments[0].classKey").value("first_timer"))
                .andExpect(jsonPath("$.segments[0].mailable").value(235))
                .andExpect(jsonPath("$.segments[0].expected.low").value(11))
                .andExpect(jsonPath("$.segments[0].expected.mid").value(23))
                .andExpect(jsonPath("$.segments[0].expected.high").value(45))
                .andExpect(jsonPath("$.smallGroupsNotShown").value(0))
                .andExpect(jsonPath("$.otherGenreInvited").value(false))
                .andExpect(jsonPath("$.otherGenreHeldBack").value(0))
                .andExpect(jsonPath("$.exclusions.legacy_unproven").value(12))
                .andExpect(jsonPath("$.exclusions.unsubscribed").value(3))
                .andExpect(jsonPath("$.exclusions.bought_this_event").value(0))
                .andExpect(jsonPath("$.exclusions.excluded_segment").value(0))
                .andExpect(jsonPath("$.exclusions.segment_cap").value(0))
                .andExpect(jsonPath("$.timing.today").value(today.toString()))
                .andExpect(jsonPath("$.timing.eventDate").value(today.plusDays(28).toString()))
                .andExpect(jsonPath("$.timing.launchDate").value(today.toString()))
                .andExpect(jsonPath("$.timing.d3Date").value(today.plusDays(25).toString()))
                .andExpect(jsonPath("$.timing.daysToEvent").value(28))
                .andExpect(jsonPath("$.timing.eventStarted").value(false))
                .andExpect(jsonPath("$.newPeople.length()").value(0))
                .andExpect(jsonPath("$.actions.length()").value(3))
                .andExpect(jsonPath("$.actions[1].type").value("invite"))
                .andExpect(jsonPath("$.actions[1].classKey").value("loyal"))
                .andExpect(jsonPath("$.actions[1].genreFit").value("same"))
                .andExpect(jsonPath("$.actions[1].holdoutPct").value(0))
                .andExpect(jsonPath("$.actions[1].arms[0].arm").value("launch"))
                .andExpect(jsonPath("$.actions[1].arms[0].date").value(today.toString()))
                .andExpect(jsonPath("$.actions[1].arms[1].arm").value("d3"))
                .andExpect(jsonPath("$.actions[1].arms[1].date").value(today.plusDays(25).toString()))
                .andExpect(jsonPath("$.actions[2].classKey").value("repeat"))
                .andExpect(jsonPath("$.actions[2].holdoutPct").value(15))
                .andExpect(jsonPath("$.actions[0].classKey").value("first_timer"))
                .andExpect(jsonPath("$.actions[0].holdoutPct").value(15))
                .andExpect(jsonPath("$.assumptions.targetPct").value(85))
                .andExpect(jsonPath("$.assumptions.ticketsPerOrder").value(1.6))
                .andExpect(jsonPath("$.assumptions.excludeSegments.length()").value(0))
                .andExpect(jsonPath("$.summary").value(nullValue()))
                .andExpect(jsonPath("$.versions.logic").value(1))
                .andExpect(jsonPath("$.versions.priors").value(1))
                .andExpect(jsonPath("$.versions.model").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void get_moreThanThreeSegments_showsTheTopThree_andCountsTheRest() throws Exception {
        Event e = event(orgA, 300);
        List<Person> people = fixture();
        people.addAll(people("lapsing", Map.of(HOUSE, 1.0), 20));
        stub(e, people);

        getPlan(e, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(3))
                .andExpect(jsonPath("$.segments[0].classKey").value("first_timer"))
                .andExpect(jsonPath("$.segments[2].classKey").value("repeat"))
                .andExpect(jsonPath("$.expected.mid").value(52))
                .andExpect(jsonPath("$.mailable").value(365))
                .andExpect(jsonPath("$.exclusions.segment_cap").value(20));
    }

    @Test
    void get_memberWithoutTaste_isCarriedAsUnknownFit() throws Exception {
        Event e = event(orgA, 300);
        List<Person> people = people("loyal", Map.of(HOUSE, 1.0), 40);
        people.addAll(people("imported", Map.of(), 12));
        stub(e, people);

        getPlan(e, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[1].classKey").value("imported"))
                .andExpect(jsonPath("$.segments[1].genreFit").value("unknown"))
                .andExpect(jsonPath("$.segments[1].reason.genreFit").value("unknown"))
                .andExpect(jsonPath("$.actions[1].classKey").value("imported"))
                .andExpect(jsonPath("$.actions[1].genreFit").value("unknown"));
    }

    @Test
    void get_realDataWithoutMailableMembers_isACold_notComputedPlan() throws Exception {
        Event e = event(orgA, 300);
        member(orgA);
        member(orgA);

        getPlan(e, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("cold"))
                .andExpect(jsonPath("$.mailable").value(0))
                .andExpect(jsonPath("$.expected").value(nullValue()))
                .andExpect(jsonPath("$.coverage.low").value(nullValue()))
                .andExpect(jsonPath("$.coverage.mid").value(nullValue()))
                .andExpect(jsonPath("$.coverage.high").value(nullValue()))
                .andExpect(jsonPath("$.coverage.verdict").value("cold"))
                .andExpect(jsonPath("$.gap.low").value(255))
                .andExpect(jsonPath("$.gap.high").value(255))
                .andExpect(jsonPath("$.segments.length()").value(0))
                .andExpect(jsonPath("$.exclusions.no_basis").value(2))
                .andExpect(jsonPath("$.actions.length()").value(1))
                .andExpect(jsonPath("$.actions[0].type").value("import_with_proof"))
                .andExpect(jsonPath("$.actions[0].classKey").value(nullValue()))
                .andExpect(jsonPath("$.actions[0].arms.length()").value(0));
    }

    // ── new people and target realism (open-data portrait) ──────────────────

    @Test
    void cold_metzHouse_listsThePortraitGroups_andATargetWithinTheTribe() throws Exception {
        Event e = event(orgA, "Metz", "House & Techno", 300);
        stub(e, List.of());

        getPlan(e, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("cold"))
                .andExpect(jsonPath("$.gap.low").value(255))
                .andExpect(jsonPath("$.newPeople.length()").value(3))
                .andExpect(jsonPath("$.newPeople[0].key").value("genre_first"))
                .andExpect(jsonPath("$.newPeople[0].origin").value("open_data"))
                .andExpect(jsonPath("$.newPeople[0].kind").value("audience"))
                .andExpect(jsonPath("$.newPeople[0].scope").value("fr_catchment"))
                .andExpect(jsonPath("$.newPeople[0].cityKeys.length()").value(3))
                .andExpect(jsonPath("$.newPeople[0].cityKeys[2]").value("nancy"))
                .andExpect(jsonPath("$.newPeople[0].size.low").value(3180))
                .andExpect(jsonPath("$.newPeople[0].size.high").value(4172))
                .andExpect(jsonPath("$.newPeople[0].method").value("electronic_first"))
                .andExpect(jsonPath("$.newPeople[0].sources[0].dataset").value("insee_age"))
                .andExpect(jsonPath("$.newPeople[0].sources[0].licence").value("Licence Ouverte 2.0"))
                .andExpect(jsonPath("$.newPeople[1].key").value("regulars"))
                .andExpect(jsonPath("$.newPeople[1].kind").value("audience"))
                .andExpect(jsonPath("$.newPeople[1].size.low").value(1113))
                .andExpect(jsonPath("$.newPeople[1].size.high").value(1460))
                .andExpect(jsonPath("$.newPeople[2].key").value("students"))
                .andExpect(jsonPath("$.newPeople[2].kind").value("context"))
                .andExpect(jsonPath("$.newPeople[2].size.low").value(52096))
                .andExpect(jsonPath("$.newPeople[2].size.high").value(52096))
                .andExpect(jsonPath("$.gapExceedsTribe").value(false))
                .andExpect(jsonPath("$.actions.length()").value(1))
                .andExpect(jsonPath("$.actions[0].type").value("import_with_proof"))
                .andExpect(jsonPath("$.actions[0].options.length()").value(0));
    }

    @Test
    void get_recordsThePortraitRequest_postDoesNot_andAnUnknownCityIsNeverRecorded() throws Exception {
        jdbc.update("delete from audience_portraits where genre_key = 'pop' and city_key in ('nancy', 'strasbourg')");
        Event e = event(orgA, "Nancy", "Pop", 300);
        Event unknown = event(orgA, "Strasbourg", "Pop", 300);
        stub(e, List.of());
        try {
            postPlan(e, null).andExpect(status().isOk());
            assertThat(portraitStatus("pop", "nancy")).isNull();

            getPlan(e, null).andExpect(status().isOk());
            assertThat(portraitStatus("pop", "nancy")).isEqualTo("pending");

            stub(unknown, List.of());
            getPlan(unknown, null).andExpect(status().isOk());
            assertThat(portraitStatus("pop", "strasbourg")).isNull();
        } finally {
            jdbc.update("delete from audience_portraits where genre_key = 'pop' and city_key in ('nancy', 'strasbourg')");
        }
    }

    @Test
    void readyResearch_joinsNewPeople_sizedFromOpenData_withTheAiMarker() throws Exception {
        String key = "rock & alternative";
        jdbc.update("delete from audience_portraits where genre_key = ? and city_key = 'thionville'", key);
        jdbc.update("""
                insert into audience_portraits (id, genre_key, city_key, status, research_groups, version, generated_at,
                  expires_at, requested_at, created_at) values (?, ?, 'thionville', 'ready', ?, 1, ?, ?, ?, ?)""",
                UUID.randomUUID(), key, """
                [{"label":"Thionville students","description":"They meet at the campus bar nights.",
                  "basis":"students","towns":["thionville"],
                  "sources":[{"url":"https://example.org/thionville","title":"Campus nights"}],"confidence":"cited"}]""",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().plus(89, ChronoUnit.DAYS)), Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        Event e = event(orgA, "Thionville", "Rock & Alternative", 300);
        stub(e, List.of());
        try {
            getPlan(e, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.newPeople.length()").value(4))
                    .andExpect(jsonPath("$.newPeople[3].key").value("research_1"))
                    .andExpect(jsonPath("$.newPeople[3].origin").value("research"))
                    .andExpect(jsonPath("$.newPeople[3].kind").value("context"))
                    .andExpect(jsonPath("$.newPeople[3].cityKeys[0]").value("thionville"))
                    .andExpect(jsonPath("$.newPeople[3].size.low").value(605))
                    .andExpect(jsonPath("$.newPeople[3].size.high").value(605))
                    .andExpect(jsonPath("$.newPeople[3].method").value("mesr_students"))
                    .andExpect(jsonPath("$.newPeople[3].sources[0].input").value("web"))
                    .andExpect(jsonPath("$.newPeople[3].sources[0].url").value("https://example.org/thionville"))
                    .andExpect(jsonPath("$.newPeople[3].research.label").value("Thionville students"))
                    .andExpect(jsonPath("$.newPeople[3].research.confidence").value("cited"))
                    .andExpect(jsonPath("$.newPeople[3].research.aiDisclosure").value("mode=ai-originated"))
                    .andExpect(jsonPath("$.newPeople[0].research").value(nullValue()));
        } finally {
            jdbc.update("delete from audience_portraits where genre_key = ? and city_key = 'thionville'", key);
        }
    }

    @Test
    void newResearchDescriptionLabelOrConfidence_recomputesThePlan_sameTextReusesIt() throws Exception {
        String key = "jazz & acoustic";
        jdbc.update("delete from audience_portraits where genre_key = ? and city_key = 'thionville'", key);
        jdbc.update("""
                insert into audience_portraits (id, genre_key, city_key, status, research_groups, version, generated_at,
                  expires_at, requested_at, created_at) values (?, ?, 'thionville', 'ready', ?, 1, ?, ?, ?, ?)""",
                UUID.randomUUID(), key, """
                [{"label":"Jazz club regulars","description":"They follow the Thursday sessions.","basis":"none",
                  "towns":[],"sources":[],"confidence":"assumed"}]""",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().plus(89, ChronoUnit.DAYS)), Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        Event e = event(orgA, "Thionville", "Jazz & Acoustic", 300);
        stub(e, List.of());
        try {
            String first = id(getPlan(e, null));
            assertThat(id(getPlan(e, null))).isEqualTo(first);

            jdbc.update("update audience_portraits set research_groups = replace(research_groups, 'Thursday', 'Friday')"
                    + " where genre_key = ? and city_key = 'thionville'", key);
            String second = id(getPlan(e, null).andExpect(
                    jsonPath("$.newPeople[3].research.description").value("They follow the Friday sessions.")));
            assertThat(second).isNotEqualTo(first);

            jdbc.update("update audience_portraits set research_groups = replace(research_groups, 'Jazz club', 'Jazz bar')"
                    + " where genre_key = ? and city_key = 'thionville'", key);
            String third = id(getPlan(e, null).andExpect(
                    jsonPath("$.newPeople[3].research.label").value("Jazz bar regulars")));
            assertThat(third).isNotIn(first, second);

            jdbc.update("update audience_portraits set research_groups = replace(research_groups, '\"assumed\"',"
                    + " '\"cited\"') where genre_key = ? and city_key = 'thionville'", key);
            String fourth = id(getPlan(e, null).andExpect(
                    jsonPath("$.newPeople[3].research.confidence").value("cited")));
            assertThat(fourth).isNotIn(first, second, third);
            assertThat(id(getPlan(e, null))).isEqualTo(fourth);
        } finally {
            jdbc.update("delete from audience_portraits where genre_key = ? and city_key = 'thionville'", key);
        }
    }

    private String portraitStatus(String genreKey, String cityKey) {
        return jdbc.query("select status from audience_portraits where genre_key = ? and city_key = ?",
                (rs, i) -> rs.getString(1), genreKey, cityKey).stream().findFirst().orElse(null);
    }

    @Test
    void cold_targetAboveTheRegulars_isFlagged_withTheRethinkOptions() throws Exception {
        // Target 85% of 2,000 = 1,700 > 1,460 regulars at most in the French part of the Metz area.
        Event e = event(orgA, "Metz", "House & Techno", 2000);
        stub(e, List.of());

        getPlan(e, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.gap.low").value(1700))
                .andExpect(jsonPath("$.gapExceedsTribe").value(true))
                .andExpect(jsonPath("$.actions.length()").value(2))
                .andExpect(jsonPath("$.actions[0].type").value("import_with_proof"))
                .andExpect(jsonPath("$.actions[1].type").value("rethink_target"))
                .andExpect(jsonPath("$.actions[1].classKey").value(nullValue()))
                .andExpect(jsonPath("$.actions[1].arms.length()").value(0))
                .andExpect(jsonPath("$.actions[1].options.length()").value(3))
                .andExpect(jsonPath("$.actions[1].options[0]").value("smaller_room"))
                .andExpect(jsonPath("$.actions[1].options[1]").value("other_date"))
                .andExpect(jsonPath("$.actions[1].options[2]").value("stronger_lineup"));
    }

    @Test
    void warm_rethinkKeepsItsSlot_withinThreeSteps() throws Exception {
        // Gap 1,700 - 93 = 1,607 > 1,460; 52/1,700 is weak, so import and rethink take two of the three steps.
        Event e = event(orgA, "Metz", "House & Techno", 2000);
        stub(e, fixture());

        getPlan(e, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("warm"))
                .andExpect(jsonPath("$.gapExceedsTribe").value(true))
                .andExpect(jsonPath("$.newPeople.length()").value(3))
                .andExpect(jsonPath("$.actions.length()").value(3))
                .andExpect(jsonPath("$.actions[0].type").value("invite"))
                .andExpect(jsonPath("$.actions[0].classKey").value("first_timer"))
                .andExpect(jsonPath("$.actions[0].options.length()").value(0))
                .andExpect(jsonPath("$.actions[1].type").value("import_with_proof"))
                .andExpect(jsonPath("$.actions[2].type").value("rethink_target"))
                .andExpect(jsonPath("$.actions[2].options.length()").value(3));
    }

    @Test
    void genreOutsideTheBuckets_hasNoNewPeople_andAnUnknownTribeVerdict() throws Exception {
        Event e = event(orgA, "Metz", "Techno", 2000);
        stub(e, List.of());

        getPlan(e, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.newPeople.length()").value(0))
                .andExpect(jsonPath("$.gapExceedsTribe").value(nullValue()))
                .andExpect(jsonPath("$.actions.length()").value(1));
    }

    @Test
    void genreWithoutAShareRate_hasNullSizes_andAnUnknownTribeVerdict() throws Exception {
        Event e = event(orgA, "Metz", "Jazz & Acoustic", 2000);
        stub(e, List.of());

        getPlan(e, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.newPeople.length()").value(3))
                .andExpect(jsonPath("$.newPeople[1].size").value(nullValue()))
                .andExpect(jsonPath("$.newPeople[1].method").value("no_genre_share_rate"))
                .andExpect(jsonPath("$.newPeople[2].size.low").value(52096))
                .andExpect(jsonPath("$.gapExceedsTribe").value(nullValue()));
    }

    @Test
    void reusedPlan_showsTheStoredGroups_andAPlanFromBeforePortraitsShowsNone() throws Exception {
        Event e = event(orgA, "Metz", "House & Techno", 300);
        stub(e, List.of());
        String first = id(getPlan(e, null));

        getPlan(e, null).andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.newPeople.length()").value(3));

        jdbc.update("update audience_plans set new_people = null, actions = ? where id = ?",
                "[{\"type\":\"import_with_proof\",\"classKey\":null,\"genreFit\":null,\"arms\":[],\"holdoutPct\":null}]",
                UUID.fromString(first));
        getPlan(e, null).andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.newPeople.length()").value(0))
                .andExpect(jsonPath("$.actions[0].options.length()").value(0));
    }

    @Test
    void changedOpenData_recomputesThePlan() throws Exception {
        Event e = event(orgA, "Metz", "House & Techno", 300);
        stub(e, List.of());
        String first = id(getPlan(e, null));
        Long seeded = jdbc.queryForObject(
                "select headline from city_open_data where city_key = 'metz' and dataset = 'insee_age'", Long.class);
        try {
            jdbc.update("update city_open_data set headline = ? where city_key = 'metz' and dataset = 'insee_age'",
                    seeded + 1000);

            String second = id(getPlan(e, null).andExpect(jsonPath("$.newPeople[0].size.low").value(3215)));
            assertThat(second).isNotEqualTo(first);
            assertThat(supersededBy(first)).isEqualTo(second);
        } finally {
            jdbc.update("update city_open_data set headline = ? where city_key = 'metz' and dataset = 'insee_age'",
                    seeded);
        }
    }

    @Test
    void aSourceDateOrStaleFlagAlone_reusesThePlan() throws Exception {
        Event e = event(orgA, "Metz", "House & Techno", 300);
        stub(e, List.of());
        String first = id(getPlan(e, null));
        Map<String, Object> seeded = jdbc.queryForMap(
                "select fetched_at, expires_at from city_open_data where city_key = 'metz' and dataset = 'insee_age'");
        try {
            jdbc.update("update city_open_data set fetched_at = ?, expires_at = ? where city_key = 'metz' and dataset = 'insee_age'",
                    Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), Timestamp.from(Instant.parse("2020-06-01T00:00:00Z")));

            assertThat(id(getPlan(e, null))).isEqualTo(first);
        } finally {
            jdbc.update("update city_open_data set fetched_at = ?, expires_at = ? where city_key = 'metz' and dataset = 'insee_age'",
                    seeded.get("fetched_at"), seeded.get("expires_at"));
        }
    }

    // ── access and refusals ────────────────────────────────────────────────

    @Test
    void anotherOrgsEvent_is404_likeAMissingOne() throws Exception {
        Event theirs = event(orgB, 300);

        getPlan(theirs, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Audience plan not found"));
        mvc.perform(get(url(UUID.randomUUID())).with(auth(owner))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Audience plan not found"));
        mvc.perform(post(url(theirs.getId())).with(auth(owner))).andExpect(status().isNotFound());
        assertThat(planRows(theirs)).isZero();
    }

    @Test
    void deletedEvent_is404() throws Exception {
        Event e = event(orgA, 300);
        jdbc.update("update events set deleted_at = now() where id = ?", e.getId());

        getPlan(e, null).andExpect(status().isNotFound());
    }

    @Test
    void killSwitchOff_is404_andNothingIsComputed() throws Exception {
        Event e = event(orgA, 300);
        props.setEnabled(false);

        getPlan(e, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Audience plan not found"));
        mvc.perform(post(url(e.getId())).with(auth(owner))).andExpect(status().isNotFound());
        assertThat(planRows(e)).isZero();
    }

    @Test
    void noEnabledCapacity_is422() throws Exception {
        Event e = event(orgA);
        tier(e, 300, false);

        getPlan(e, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("AUDIENCE_PLAN_NO_CAPACITY"));
        assertThat(planRows(e)).isZero();
    }

    @Test
    void aTargetThatRoundsToZero_is422() throws Exception {
        Event e = event(orgA, 1);
        stub(e, fixture());

        postPlan(e, "{\"targetPct\":1}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("AUDIENCE_PLAN_NO_CAPACITY"));
    }

    @Test
    void eventWithoutStartDate_is409() throws Exception {
        Event e = event(orgA, 300);
        jdbc.update("update events set starts_at = null where id = ?", e.getId());

        getPlan(e, null).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void invalidOverrides_are400_andWriteNothing() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());

        postPlan(e, "{\"targetPct\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.targetPct").exists());
        postPlan(e, "{\"targetPct\":101}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.targetPct").exists());
        postPlan(e, "{\"assumptions\":{\"ticketsPerOrder\":0.9}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields['assumptions.ticketsPerOrder']").exists());
        postPlan(e, "{\"assumptions\":{\"ticketsPerOrder\":10.1}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields['assumptions.ticketsPerOrder']").exists());
        postPlan(e, "{\"excludeSegments\":[\"vip\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.excludeSegments").value("unknown class: vip"));
        postPlan(e, "{\"excludeSegments\":[null]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.excludeSegments").value("unknown class: null"));
        getPlan(e, "de").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.locale").exists());
        assertThat(planRows(e)).isZero();
    }

    @Test
    void boundaryOverrides_areAccepted() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());

        postPlan(e, "{\"targetPct\":100,\"assumptions\":{\"ticketsPerOrder\":10.0}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTickets").value(300))
                .andExpect(jsonPath("$.assumptions.ticketsPerOrder").value(10.0));
        postPlan(e, "{\"targetPct\":1,\"assumptions\":{\"ticketsPerOrder\":1.0}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTickets").value(3))
                .andExpect(jsonPath("$.assumptions.ticketsPerOrder").value(1.0));
    }

    // ── reuse, recompute, supersede ─────────────────────────────────────────

    @Test
    void unchangedInputs_reuseTheStoredPlan() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());

        String first = id(getPlan(e, null));
        String second = id(getPlan(e, null));

        assertThat(second).isEqualTo(first);
        assertThat(planRows(e)).isEqualTo(1);
    }

    @Test
    void locale_isNotPartOfThePlan_frThenEnReuseOneRow() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());

        String fr = id(getPlan(e, "fr").andExpect(jsonPath("$.summary").value(nullValue())));
        String en = id(getPlan(e, "en"));

        assertThat(en).isEqualTo(fr);
        assertThat(planRows(e)).isEqualTo(1);
    }

    @Test
    void locale_selectsThatLocalesStoredSummary() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());
        String id = id(getPlan(e, null));
        jdbc.update("update audience_plans set summaries = ? where id = ?",
                "{\"fr\":{\"headline\":\"Titre\",\"segmentLines\":[],\"gapLine\":\"Ecart\",\"actions\":[],\"assumptions\":[]}}",
                UUID.fromString(id));

        getPlan(e, "FR").andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.summary.headline").value("Titre"));
        getPlan(e, "uk").andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.summary").value(nullValue()));
    }

    @Test
    void changedTiers_recomputeAndSupersede() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());
        String first = id(getPlan(e, null));

        jdbc.update("update ticket_tiers set quantity = 400 where event_id = ?", e.getId());
        String second = id(getPlan(e, null).andExpect(jsonPath("$.capacity").value(400)));

        assertThat(second).isNotEqualTo(first);
        assertThat(supersededBy(first)).isEqualTo(second);
        assertThat(supersededBy(second)).isNull();
    }

    @Test
    void changedMailableCount_recomputes() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());
        String first = id(getPlan(e, null));

        List<Person> more = fixture();
        more.addAll(people("loyal", Map.of(HOUSE, 1.0), 1));
        stub(e, more);
        String second = id(getPlan(e, null).andExpect(jsonPath("$.mailable").value(346)));

        assertThat(second).isNotEqualTo(first);
        assertThat(supersededBy(first)).isEqualTo(second);
    }

    @Test
    void aPlanOlderThan24Hours_isRecomputed_under24IsReused() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());
        String first = id(getPlan(e, null));

        backdate(first, 23);
        assertThat(id(getPlan(e, null))).isEqualTo(first);

        backdate(first, 25);
        String second = id(getPlan(e, null));
        assertThat(second).isNotEqualTo(first);
        assertThat(supersededBy(first)).isEqualTo(second);
    }

    @Test
    void post_alwaysWritesANewPlan_keepsOmittedValues_andGetKeepsTheOverrides() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());
        String a = id(getPlan(e, null));

        String b = id(postPlan(e, "{\"targetPct\":90}").andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTickets").value(270))
                .andExpect(jsonPath("$.assumptions.targetPct").value(90))
                .andExpect(jsonPath("$.assumptions.ticketsPerOrder").value(1.6)));
        String c = id(postPlan(e, "{\"excludeSegments\":[\"first_timer\",\"repeat\"],\"assumptions\":{\"ticketsPerOrder\":2.0}}")
                .andExpect(jsonPath("$.assumptions.targetPct").value(90))
                .andExpect(jsonPath("$.assumptions.ticketsPerOrder").value(2.0))
                .andExpect(jsonPath("$.assumptions.excludeSegments[0]").value("first_timer"))
                .andExpect(jsonPath("$.assumptions.excludeSegments[1]").value("repeat"))
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.mailable").value(345))
                .andExpect(jsonPath("$.exclusions.excluded_segment").value(305)));
        String d = id(postPlan(e, null).andExpect(status().isOk()));

        assertThat(List.of(a, b, c, d)).doesNotHaveDuplicates();
        assertThat(supersededBy(a)).isEqualTo(b);
        assertThat(supersededBy(b)).isEqualTo(c);
        assertThat(supersededBy(c)).isEqualTo(d);
        assertThat(supersededBy(d)).isNull();

        getPlan(e, null).andExpect(jsonPath("$.id").value(d))
                .andExpect(jsonPath("$.assumptions.targetPct").value(90))
                .andExpect(jsonPath("$.assumptions.excludeSegments.length()").value(2));

        String cleared = id(postPlan(e, "{\"excludeSegments\":[]}")
                .andExpect(jsonPath("$.assumptions.excludeSegments.length()").value(0))
                .andExpect(jsonPath("$.segments.length()").value(3)));
        assertThat(supersededBy(d)).isEqualTo(cleared);
    }

    @Test
    void staleRecompute_afterAPost_keepsThePostsOverrides() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());
        String a = id(getPlan(e, null));
        String b = id(postPlan(e, "{\"targetPct\":90,\"excludeSegments\":[\"repeat\"]}"));
        backdate(b, 25);

        String c = id(getPlan(e, null).andExpect(jsonPath("$.assumptions.targetPct").value(90))
                .andExpect(jsonPath("$.assumptions.excludeSegments[0]").value("repeat")));

        assertThat(List.of(a, b, c)).doesNotHaveDuplicates();
        assertThat(supersededBy(b)).isEqualTo(c);
    }

    @Test
    void recomputeAndPost_waitOnTheCurrentPlansLock_andTheOverrideSurvives() throws Exception {
        Event e = event(orgA, 300);
        stub(e, fixture());
        String a = id(getPlan(e, null));
        backdate(a, 25);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                assertThat(planRepo.lockCurrent(orgA, e.getId())).hasSize(1);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<String> posted = pool.submit(() -> id(postPlan(e, "{\"targetPct\":90}")));
            Future<String> refreshed = pool.submit(() -> id(getPlan(e, null)));
            Thread.sleep(300);
            assertThat(posted.isDone()).as("POST waits for the row lock").isFalse();
            assertThat(refreshed.isDone()).as("stale GET waits for the row lock").isFalse();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            posted.get(10, TimeUnit.SECONDS);
            refreshed.get(10, TimeUnit.SECONDS);

            // Whichever went second read the other's committed row, so the override is current either way.
            getPlan(e, null).andExpect(jsonPath("$.assumptions.targetPct").value(90));
            assertThat(jdbc.queryForObject("select count(*) from audience_plans where event_id = ? and superseded_by is null",
                    Integer.class, e.getId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void storedPlanHoldsNoMembershipIds() throws Exception {
        Event e = event(orgA, 300);
        List<Person> people = fixture();
        stub(e, people);
        String id = id(getPlan(e, null));

        String stored = jdbc.queryForList("select * from audience_plans where id = ?", UUID.fromString(id)).toString()
                + jdbc.queryForList("select * from audience_plan_segments where plan_id = ?", UUID.fromString(id));
        for (Person p : people) assertThat(stored).doesNotContain(p.membershipId().toString());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static List<Person> people(String classKey, Map<String, Double> taste, int n) {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Person(UUID.randomUUID(), classKey, taste, 0, false, false, 0, 0));
        return out;
    }

    /** Checked warm pass: 40 loyal, 70 repeat, 235 first-timers, all genre fit same. */
    private static List<Person> fixture() {
        List<Person> all = new ArrayList<>();
        all.addAll(people("loyal", Map.of(HOUSE, 1.0), 40));
        all.addAll(people("repeat", Map.of(HOUSE, 1.0), 70));
        all.addAll(people("first_timer", Map.of(HOUSE, 1.0), 235));
        return all;
    }

    private void stub(Event e, List<Person> people) {
        Map<String, Integer> gate = new LinkedHashMap<>();
        gate.put("legacy_unproven", 12);
        gate.put("unsubscribed", 3);
        doReturn(new CandidateBuilder.Input(e.getOrgId(), e.getGenreKey(), 255, 1.6, gate, people))
                .when(loader).input(eq(e.getOrgId()), any(), anyInt(), anyDouble());
    }

    private ResultActions getPlan(Event e, String locale) throws Exception {
        var req = get(url(e.getId())).with(auth(owner));
        if (locale != null) req = req.param("locale", locale);
        return mvc.perform(req);
    }

    private ResultActions postPlan(Event e, String body) throws Exception {
        var req = post(url(e.getId())).with(auth(owner));
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private static String url(UUID eventId) {
        return "/api/v1/events/" + eventId + "/audience-plan";
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private int planRows(Event e) {
        return jdbc.queryForObject("select count(*) from audience_plans where event_id = ?", Integer.class, e.getId());
    }

    private String supersededBy(String planId) {
        UUID next = jdbc.queryForObject("select superseded_by from audience_plans where id = ?", UUID.class,
                UUID.fromString(planId));
        return next == null ? null : next.toString();
    }

    private void backdate(String planId, int hours) {
        jdbc.update("update audience_plans set created_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(hours, ChronoUnit.HOURS)), UUID.fromString(planId));
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("Plan Org");
        o.setSlug("plan-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("plan@example.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    private void member(UUID orgId) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("plan-" + UUID.randomUUID() + "@example.com");
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumerRepo.save(c).getConsumerId());
        membershipRepo.save(m);
    }

    /** A live house night 28 days out (20:00 Paris), no on-sale date, with the given enabled tiers. */
    private Event event(UUID orgId, int... quantities) {
        return event(orgId, null, "House & Techno", quantities);
    }

    /** The same night in {@code venueCity} (null = none) with {@code genre}. */
    private Event event(UUID orgId, String venueCity, String genre, int... quantities) {
        User u = new User();
        u.setEmail("plan-owner-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        u = userRepo.save(u);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Plan Night");
        e.setSlug("plan-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setGenre(genre);
        if (venueCity != null) e.setVenueCity(venueCity);
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minusSeconds(3600));
        e.setCreatedBy(u.getId());
        e.setCurrency("EUR");
        e.setTimezone("Europe/Paris");
        e.setStartsAt(today.plusDays(28).atTime(20, 0).atZone(PARIS).toInstant());
        e = eventRepo.save(e);
        for (int q : quantities) tier(e, q, true);
        return e;
    }

    private void tier(Event e, int quantity, boolean enabled) {
        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("GA " + quantity);
        t.setPriceMinor(2000);
        t.setQuantity(quantity);
        t.setEnabled(enabled);
        tierRepo.save(t);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
