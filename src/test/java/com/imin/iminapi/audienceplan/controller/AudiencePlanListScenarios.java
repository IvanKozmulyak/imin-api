package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.service.CandidateLoader;
import com.imin.iminapi.audienceplan.service.PlanRefreshJob;
import com.imin.iminapi.audienceplan.service.PlanService;
import com.imin.iminapi.audienceplan.service.PlanService.Refresh;
import com.imin.iminapi.audienceplan.service.PortraitService;
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
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
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
import java.time.Clock;
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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/audience-plans} and the plan refresh (publish listener, daily job) on H2 and Postgres 17.
 * Candidate people are stubbed with the checked warm fixture where numbers matter; one test uses real data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
abstract class AudiencePlanListScenarios {

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
    @Autowired PlanService planService;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;
    @MockitoSpyBean CandidateLoader loader;
    @MockitoSpyBean PortraitService portraits;
    @MockitoBean AuditLogger auditLogger;

    private final List<UUID> orgs = new ArrayList<>();
    protected UUID orgA;
    private UUID orgB;
    private AuthPrincipal owner;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        orgA = org();
        orgB = org();
        owner = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
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
            jdbc.update("delete from event_outcomes where event_id in (select id from events where org_id = ?)", org);
            jdbc.update("delete from ticket_tiers where event_id in (select id from events where org_id = ?)", org);
            jdbc.update("delete from events where org_id = ?", org);
            jdbc.update("delete from users where org_id = ?", org);
            jdbc.update("delete from organizations where id = ?", org);
        }
        orgs.clear();
    }

    // ── list ───────────────────────────────────────────────────────────────

    @Test
    void list_orgScopedUpcomingDatedDraftsAndLive_soonestFirst_withoutPlans() throws Exception {
        Event live = event(orgA, EventStatus.LIVE, 28, 300);
        Event draft = event(orgA, EventStatus.DRAFT, 10, 300);
        event(orgA, EventStatus.LIVE, -2, 300);
        event(orgA, EventStatus.CANCELLED, 5, 300);
        event(orgA, EventStatus.PAST, 6, 300);
        Event undated = event(orgA, EventStatus.DRAFT, 7, 300);
        undated.setStartsAt(null);
        eventRepo.save(undated);
        Event deleted = event(orgA, EventStatus.LIVE, 8, 300);
        deleted.setDeletedAt(Instant.now());
        eventRepo.save(deleted);
        event(orgB, EventStatus.LIVE, 9, 300);

        list(null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventId").value(draft.getId().toString()))
                .andExpect(jsonPath("$[0].name").value("Plan Night"))
                .andExpect(jsonPath("$[0].startsAt").value(notNullValue()))
                .andExpect(jsonPath("$[0].eventStatus").value("draft"))
                .andExpect(jsonPath("$[0].status").value("none"))
                .andExpect(jsonPath("$[0].coverageMid").value(nullValue()))
                .andExpect(jsonPath("$[0].verdict").value(nullValue()))
                .andExpect(jsonPath("$[0].computedAt").value(nullValue()))
                .andExpect(jsonPath("$[1].eventId").value(live.getId().toString()))
                .andExpect(jsonPath("$[1].eventStatus").value("live"))
                .andExpect(jsonPath("$[1].status").value("none"));
        assertThat(Instant.parse(JsonPath.read(body(list(null)), "$[1].startsAt")))
                .isEqualTo(live.getStartsAt());
    }

    @Test
    void list_fromInTheFuture_dropsEarlierEvents_andAPastFromIsRaisedToNow() throws Exception {
        event(orgA, EventStatus.LIVE, 5, 300);
        Event later = event(orgA, EventStatus.LIVE, 28, 300);
        event(orgA, EventStatus.LIVE, -1, 300);

        list(today.plusDays(15).atStartOfDay(PARIS).toInstant().toString())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(later.getId().toString()));
        list("2020-01-01T00:00:00Z").andExpect(jsonPath("$.length()").value(2));
        list(" ").andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void list_badFrom_is400() throws Exception {
        list("tomorrow").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }

    @Test
    void list_killSwitchOff_is404_evenForABadFrom() throws Exception {
        event(orgA, EventStatus.LIVE, 28, 300);
        props.setEnabled(false);
        list(null).andExpect(status().isNotFound());
        list("tomorrow").andExpect(status().isNotFound());
    }

    @Test
    void list_freshRightAfterAGet_withTheStoredCoverageAndVerdict_andWritesNothing() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 200, 100);
        stub(e, fixture());
        getPlan(e).andExpect(status().isOk());
        Instant computed = jdbc.queryForObject("select created_at from audience_plans where event_id = ?",
                Timestamp.class, e.getId()).toInstant();

        list(null).andExpect(jsonPath("$[0].status").value("fresh"))
                .andExpect(jsonPath("$[0].coverageMid").value(0.20))
                .andExpect(jsonPath("$[0].verdict").value("medium"));
        assertThat(Instant.parse(JsonPath.read(body(list(null)), "$[0].computedAt"))).isEqualTo(computed);
        assertThat(planRows(e)).isEqualTo(1);
    }

    @Test
    void list_staleAfter24h_withoutRecomputing() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        stub(e, fixture());
        String id = id(getPlan(e));
        backdate(id, 25);

        list(null).andExpect(jsonPath("$[0].status").value("stale"))
                .andExpect(jsonPath("$[0].verdict").value("medium"));
        assertThat(planRows(e)).isEqualTo(1);
    }

    @Test
    void list_staleAfterAnInputChange_withoutRecomputing() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        stub(e, fixture());
        getPlan(e).andExpect(status().isOk());
        tier(e, 50, true);

        list(null).andExpect(jsonPath("$[0].status").value("stale"));
        assertThat(planRows(e)).isEqualTo(1);
    }

    @Test
    void list_staleWhenTheOrgsMailableCountMoved() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        stub(e, fixture());
        getPlan(e).andExpect(status().isOk());
        doReturn(346).when(loader).mailableCount(orgA);

        list(null).andExpect(jsonPath("$[0].status").value("stale"));
    }

    @Test
    void list_coldPlan_hasNoCoverageMid_andVerdictCold() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        stub(e, List.of());
        getPlan(e).andExpect(jsonPath("$.mode").value("cold"));

        list(null).andExpect(jsonPath("$[0].status").value("fresh"))
                .andExpect(jsonPath("$[0].coverageMid").value(nullValue()))
                .andExpect(jsonPath("$[0].verdict").value("cold"));
    }

    @Test
    void list_realData_agreesWithTheGetOnFreshness() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        member(orgA);
        getPlan(e).andExpect(status().isOk());

        list(null).andExpect(jsonPath("$[0].status").value("fresh"));
    }

    @Test
    void list_metzPlanJustComputed_withItsPortrait_isFresh() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        stub(e, List.of());
        getPlan(e).andExpect(jsonPath("$.newPeople.length()").value(3))
                .andExpect(jsonPath("$.newPeople[1].size.high").value(1460));

        list(null).andExpect(jsonPath("$[0].status").value("fresh"));
        assertThat(planRows(e)).isEqualTo(1);
    }

    @Test
    void list_staleWhenThePortraitMoved() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        stub(e, List.of());
        getPlan(e).andExpect(status().isOk());
        Long seeded = jdbc.queryForObject(
                "select headline from city_open_data where city_key = 'metz' and dataset = 'insee_age'", Long.class);
        try {
            jdbc.update("update city_open_data set headline = ? where city_key = 'metz' and dataset = 'insee_age'",
                    seeded + 1000);

            list(null).andExpect(jsonPath("$[0].status").value("stale"));
        } finally {
            jdbc.update("update city_open_data set headline = ? where city_key = 'metz' and dataset = 'insee_age'",
                    seeded);
        }
    }

    @Test
    void list_readsThePortraitOncePerGenreAndCity() throws Exception {
        Event first = event(orgA, EventStatus.LIVE, 20, 300);
        Event second = event(orgA, EventStatus.LIVE, 28, 300);
        stub(first, List.of());
        getPlan(first).andExpect(status().isOk());
        getPlan(second).andExpect(status().isOk());
        clearInvocations(portraits);

        list(null).andExpect(jsonPath("$[0].status").value("fresh"))
                .andExpect(jsonPath("$[1].status").value("fresh"));
        verify(portraits, times(1)).forCity(HOUSE, "metz");
    }

    // ── refresh ────────────────────────────────────────────────────────────

    @Test
    void publishingThroughTheApi_computesOnePlanInTheBackground() throws Exception {
        Event e = event(orgA, EventStatus.DRAFT, 28, 300);
        stub(e, fixture());

        mvc.perform(post("/api/v1/events/" + e.getId() + "/publish").with(auth(owner)))
                .andExpect(status().isOk());

        awaitPlanRows(e, 1);
        assertThat(planService.refresh(e.getId())).isEqualTo(Refresh.UNCHANGED);
        assertThat(planRows(e)).isEqualTo(1);
    }

    @Test
    void aRolledBackPublish_neverPlans() {
        Event rolledBack = event(orgA, EventStatus.LIVE, 28, 300);
        Event committed = event(orgA, EventStatus.LIVE, 20, 300);
        stub(rolledBack, fixture());

        tx.executeWithoutResult(s -> {
            publisher.publishEvent(new PredictorReactivityEvents.EventPublished(rolledBack.getId()));
            s.setRollbackOnly();
        });
        tx.executeWithoutResult(s -> publisher.publishEvent(new PredictorReactivityEvents.EventPublished(committed.getId())));

        // One refresh thread: the committed event's plan lands after anything queued before it.
        awaitPlanRows(committed, 1);
        assertThat(planRows(rolledBack)).isZero();
    }

    @Test
    void refresh_unchangedInputs_writeNoNewRow_andChangedInputsSupersede() {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        stub(e, fixture());

        assertThat(planService.refresh(e.getId())).isEqualTo(Refresh.CREATED);
        assertThat(planService.refresh(e.getId())).isEqualTo(Refresh.UNCHANGED);
        assertThat(planRows(e)).isEqualTo(1);
        UUID first = currentPlan(e);

        tier(e, 50, true);
        assertThat(planService.refresh(e.getId())).isEqualTo(Refresh.CREATED);
        assertThat(planRows(e)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select superseded_by from audience_plans where id = ?", UUID.class, first))
                .isEqualTo(currentPlan(e));
    }

    @Test
    void refresh_skipsWithoutWriting() {
        Event started = event(orgA, EventStatus.LIVE, -1, 300);
        Event undated = event(orgA, EventStatus.LIVE, 28, 300);
        undated.setStartsAt(null);
        eventRepo.save(undated);
        Event noCapacity = event(orgA, EventStatus.LIVE, 28);
        tier(noCapacity, 100, false);
        Event deleted = event(orgA, EventStatus.LIVE, 28, 300);
        deleted.setDeletedAt(Instant.now());
        eventRepo.save(deleted);

        assertThat(planService.refresh(started.getId())).isEqualTo(Refresh.SKIPPED);
        assertThat(planService.refresh(undated.getId())).isEqualTo(Refresh.SKIPPED);
        assertThat(planService.refresh(noCapacity.getId())).isEqualTo(Refresh.SKIPPED);
        assertThat(planService.refresh(deleted.getId())).isEqualTo(Refresh.SKIPPED);
        assertThat(planService.refresh(UUID.randomUUID())).isEqualTo(Refresh.SKIPPED);
        for (Event e : List.of(started, undated, noCapacity, deleted)) assertThat(planRows(e)).isZero();
    }

    @Test
    void refresh_killSwitchOff_skipsWithoutWriting() {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        stub(e, fixture());
        props.setEnabled(false);

        assertThat(planService.refresh(e.getId())).isEqualTo(Refresh.SKIPPED);
        assertThat(planRows(e)).isZero();
    }

    @Test
    void dailyRun_plansOnSaleEventsOnly_andASecondRunTheSameDayWritesNothing() {
        Event onSale = event(orgA, EventStatus.LIVE, 28, 300);
        Event draft = event(orgA, EventStatus.DRAFT, 28, 300);
        Event closed = event(orgA, EventStatus.LIVE, 28, 300);
        closed.setSaleClosesAt(Instant.now().minus(1, ChronoUnit.HOURS));
        eventRepo.save(closed);
        Event notYet = event(orgA, EventStatus.LIVE, 28, 300);
        notYet.setOnSaleAt(Instant.now().plus(2, ChronoUnit.DAYS));
        eventRepo.save(notYet);
        stub(onSale, fixture());
        // Direct instance: the proxied bean's scheduler lock would skip a second run inside its minimum hold.
        PlanRefreshJob job = new PlanRefreshJob(planService, eventRepo, clock, null);

        PlanRefreshJob.Result first = job.run();
        assertThat(planRows(onSale)).isEqualTo(1);
        assertThat(first.created()).isGreaterThanOrEqualTo(1);
        for (Event e : List.of(draft, closed, notYet)) assertThat(planRows(e)).isZero();

        PlanRefreshJob.Result second = job.run();
        assertThat(planRows(onSale)).isEqualTo(1);
        assertThat(second.unchanged()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void firstPlan_aRefreshAndAGetRacing_waitOnTheFirstPlanLock_andWriteOnePlan() throws Exception {
        Event e = event(orgA, EventStatus.LIVE, 28, 300);
        stub(e, fixture());

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                planService.lockFirstPlan(e.getId());
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Refresh> refreshed = pool.submit(() -> planService.refresh(e.getId()));
            Future<String> got = pool.submit(() -> id(getPlan(e)));
            Thread.sleep(300);
            assertThat(refreshed.isDone()).as("refresh waits for the first-plan lock").isFalse();
            assertThat(got.isDone()).as("first GET waits for the first-plan lock").isFalse();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            Refresh r = refreshed.get(10, TimeUnit.SECONDS);
            got.get(10, TimeUnit.SECONDS);

            assertThat(r).isIn(Refresh.CREATED, Refresh.UNCHANGED);
            assertThat(planRows(e)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void awaitPlanRows(Event e, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (planRows(e) < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        assertThat(planRows(e)).isEqualTo(expected);
    }

    private static List<Person> people(String classKey, int n) {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Person(UUID.randomUUID(), classKey, Map.of(HOUSE, 1.0), 0, false, false, 0, 0));
        }
        return out;
    }

    /** Checked warm pass: 40 loyal, 70 repeat, 235 first-timers, all genre fit same. */
    private static List<Person> fixture() {
        List<Person> all = new ArrayList<>();
        all.addAll(people("loyal", 40));
        all.addAll(people("repeat", 70));
        all.addAll(people("first_timer", 235));
        return all;
    }

    /** Stubs the loader for the org; the list reads the same mailable count the stubbed plan hashed. */
    private void stub(Event e, List<Person> people) {
        Map<String, Integer> gate = new LinkedHashMap<>();
        gate.put("legacy_unproven", 12);
        doReturn(new CandidateBuilder.Input(e.getOrgId(), e.getGenreKey(), 255, 1.6, gate, people))
                .when(loader).input(eq(e.getOrgId()), any(), anyInt(), anyDouble());
        doReturn(people.size()).when(loader).mailableCount(e.getOrgId());
    }

    private ResultActions list(String from) throws Exception {
        var req = get("/api/v1/audience-plans").with(auth(owner));
        if (from != null) req = req.param("from", from);
        return mvc.perform(req);
    }

    private ResultActions getPlan(Event e) throws Exception {
        return mvc.perform(get("/api/v1/events/" + e.getId() + "/audience-plan").with(auth(owner)));
    }

    private static String body(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r), "$.id");
    }

    private int planRows(Event e) {
        return jdbc.queryForObject("select count(*) from audience_plans where event_id = ?", Integer.class, e.getId());
    }

    private UUID currentPlan(Event e) {
        return jdbc.queryForObject("select id from audience_plans where event_id = ? and superseded_by is null",
                UUID.class, e.getId());
    }

    private void backdate(String planId, int hours) {
        jdbc.update("update audience_plans set created_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(hours, ChronoUnit.HOURS)), UUID.fromString(planId));
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("Plan List Org");
        o.setSlug("plan-list-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("plan@example.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    private void member(UUID orgId) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("plan-list-" + UUID.randomUUID() + "@example.com");
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumerRepo.save(c).getConsumerId());
        membershipRepo.save(m);
    }

    /** A house night {@code days} out at 20:00 Paris with free enabled tiers, publishable as is. */
    protected Event event(UUID orgId, EventStatus status, int days, int... quantities) {
        User u = new User();
        u.setEmail("plan-list-owner-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        u = userRepo.save(u);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Plan Night");
        e.setSlug("plan-list-" + UUID.randomUUID().toString().substring(0, 12));
        e.setGenre("House & Techno");
        e.setDescription("A night.");
        e.setVenueStreet("1 rue Test");
        e.setVenueCity("Metz");
        e.setVenuePostalCode("57000");
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        if (status != EventStatus.DRAFT) e.setPublishedAt(Instant.now().minusSeconds(3600));
        e.setCreatedBy(u.getId());
        e.setCurrency("EUR");
        e.setTimezone("Europe/Paris");
        Instant starts = today.plusDays(days).atTime(20, 0).atZone(PARIS).toInstant();
        e.setStartsAt(starts);
        e.setEndsAt(starts.plus(6, ChronoUnit.HOURS));
        e = eventRepo.save(e);
        for (int q : quantities) tier(e, q, true);
        return e;
    }

    private void tier(Event e, int quantity, boolean enabled) {
        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("GA " + quantity);
        t.setPriceMinor(0);
        t.setQuantity(quantity);
        t.setEnabled(enabled);
        tierRepo.save(t);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
