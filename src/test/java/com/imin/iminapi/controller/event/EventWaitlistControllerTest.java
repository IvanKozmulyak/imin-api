package com.imin.iminapi.controller.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.event.WaitlistAddTicketsRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifyReleaseRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.event.EventWaitlistService;
import com.imin.iminapi.service.event.InventoryService;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The organizer's notify-me list: counts and last email, Add tickets, roles and org scoping. */
@IminIntegrationTest
class EventWaitlistControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired EventRepository events;
    @Autowired UserRepository users;
    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired NotifyReleaseRepository releases;
    @Autowired AuditRows auditRows;
    @Autowired EventWaitlistService waitlist;
    @Autowired InventoryService inventory;

    final ObjectMapper om = new ObjectMapper();
    final List<UUID> orgIds = new ArrayList<>();

    Organization org;
    User owner;
    Event event;
    TicketTier tier;

    @BeforeEach
    void setUp() {
        org = newOrg();
        owner = fx.owner(org);
        event = liveEvent(org, owner);
        tier = fx.tier(event, 2500, 10);
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    // -----------------------------------------------------------------------
    // GET
    // -----------------------------------------------------------------------

    /**
     * Waiting counts this event's subscriptions still owed an email; the last email is the latest release, with
     * every email it sent and the time of its last one.
     */
    @Test
    void get_countsPendingSubscriptions_andShowsTheLatestReleasesLastSend() throws Exception {
        subscribe(event, null);
        subscribe(event, null);
        subscribe(event, Instant.now().minus(Duration.ofHours(2)));
        subscribe(liveEvent(org, owner), null);
        Instant firstRelease = Instant.parse("2026-09-01T18:00:00Z");
        Instant secondRelease = Instant.parse("2026-09-03T18:30:00Z");
        releases.recordSends(UUID.randomUUID(), event.getId(), firstRelease, 40);
        releases.closeOpen(event.getId(), firstRelease.plus(Duration.ofHours(1)));
        releases.recordSends(UUID.randomUUID(), event.getId(), secondRelease, 3);
        releases.recordSends(UUID.randomUUID(), event.getId(), secondRelease.plus(Duration.ofMinutes(40)), 2);

        mvc.perform(get(url(event)).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting").value(2))
                .andExpect(jsonPath("$.lastEmail.sentCount").value(5))
                .andExpect(jsonPath("$.lastEmail.sentAt").value("2026-09-03T19:10:00Z"));
    }

    @Test
    void get_hasNoLastEmail_beforeAnyRelease_andIsReadableByAMember() throws Exception {
        subscribe(event, null);

        mvc.perform(get(url(event)).with(as(member(org))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting").value(1))
                .andExpect(jsonPath("$.lastEmail").doesNotExist());
    }

    @Test
    void get_anotherOrgsEvent_isLeakSafe404() throws Exception {
        Organization other = newOrg();
        User otherOwner = fx.owner(other);

        mvc.perform(get(url(event)).with(as(otherOwner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // -----------------------------------------------------------------------
    // POST /tickets
    // -----------------------------------------------------------------------

    @Test
    void addTickets_raisesTheTierAndIsAudited() throws Exception {
        mvc.perform(post(url(event) + "/tickets").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tier.getId(), 25)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tier.getId().toString()))
                .andExpect(jsonPath("$.quantity").value(35));

        assertThat(quantityOf(tier)).isEqualTo(35);
        auditRows.assertRecorded(org.getId(), AuditActions.TIER_UPDATED, "tier", tier.getId());
    }

    @Test
    void addTickets_byAMember_isForbidden() throws Exception {
        mvc.perform(post(url(event) + "/tickets").with(as(member(org)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tier.getId(), 5)))
                .andExpect(status().isForbidden());

        assertThat(quantityOf(tier)).isEqualTo(10);
    }

    @ParameterizedTest(name = "tierId={0}, addTickets={1}")
    @CsvSource(nullValues = "null", value = {
            "null, 5, tierId",
            "tier, 0, addTickets",
            "tier, 501, addTickets",
            "tier, null, addTickets"})
    void addTickets_rejectsAMissingTierOrACountOutside1To500(String tierRef, Integer n, String field) throws Exception {
        UUID tierId = tierRef == null ? null : tier.getId();

        mvc.perform(post(url(event) + "/tickets").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tierId, n)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields." + field).exists());

        assertThat(quantityOf(tier)).isEqualTo(10);
    }

    @Test
    void addTickets_toATierOfAnotherEvent_is404() throws Exception {
        TicketTier elsewhere = fx.tier(liveEvent(org, owner), 2500, 10);

        mvc.perform(post(url(event) + "/tickets").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(elsewhere.getId(), 5)))
                .andExpect(status().isNotFound());

        assertThat(quantityOf(elsewhere)).isEqualTo(10);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventStatus.class, names = {"DRAFT", "PAST", "CANCELLED"})
    void addTickets_onAnEventThatIsNotLive_isRefused(EventStatus status) throws Exception {
        event.setStatus(status);
        events.save(event);

        mvc.perform(post(url(event) + "/tickets").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tier.getId(), 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fields.event").value("not_live"));

        assertThat(quantityOf(tier)).isEqualTo(10);
    }

    /**
     * A checkout hold that lands while Add tickets is running waits for it, so the add's full-tier save
     * cannot write back the hold count it read before. Add tickets is paused at its audit row, which is
     * written after the tier is read and before the tier is saved.
     */
    @Test
    void addTickets_neverLosesACheckoutHoldThatLandsMeanwhile() throws Exception {
        ExecutorService adder = Executors.newSingleThreadExecutor();
        ExecutorService buyer = Executors.newSingleThreadExecutor();
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "audit_logs", "target_id", tier.getId())) {
            try {
                Future<?> add = adder.submit(() ->
                        waitlist.addTickets(fx.principal(owner), event.getId(),
                                new WaitlistAddTicketsRequest(tier.getId(), 5)));
                pause.awaitBlocked(Duration.ofSeconds(20));
                Future<UUID> hold = buyer.submit(() ->
                        inventory.reserve(tier.getId(), 1, Instant.now().plus(Duration.ofMinutes(30)), null));
                try {
                    hold.get(2, TimeUnit.SECONDS);
                } catch (TimeoutException waitingForTheTierLock) {
                    // Expected: the hold queues behind the add.
                }
                pause.release();
                add.get(30, TimeUnit.SECONDS);
                hold.get(30, TimeUnit.SECONDS);
            } finally {
                pause.release();
                adder.shutdownNow();
                buyer.shutdownNow();
                adder.awaitTermination(30, TimeUnit.SECONDS);
                buyer.awaitTermination(30, TimeUnit.SECONDS);
            }
        }

        Map<String, Object> row = jdbc.queryForMap("SELECT quantity, reserved FROM ticket_tiers WHERE id = ?", tier.getId());
        assertThat(row.get("quantity")).isEqualTo(15);
        assertThat(row.get("reserved")).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private Organization newOrg() {
        Organization o = fx.org();
        orgIds.add(o.getId());
        return o;
    }

    private Event liveEvent(Organization o, User createdBy) {
        Event e = fx.event(o, createdBy, EventStatus.LIVE, Instant.now().plus(Duration.ofDays(20)));
        e.setPublishedAt(Instant.now().minus(Duration.ofDays(3)));
        return events.save(e);
    }

    private User member(Organization o) {
        User u = new User();
        u.setEmail(fx.email("member"));
        u.setOrgId(o.getId());
        u.setRole(UserRole.MEMBER);
        return users.save(u);
    }

    private void subscribe(Event e, Instant notifiedAt) {
        NotifySubscription s = new NotifySubscription();
        s.setEventId(e.getId());
        s.setEmail(fx.email("fan"));
        s.setEveryRelease(true);
        s.setNotifiedAt(notifiedAt);
        subscriptions.save(s);
    }

    private Integer quantityOf(TicketTier t) {
        return jdbc.queryForObject("SELECT quantity FROM ticket_tiers WHERE id = ?", Integer.class, t.getId());
    }

    private static String url(Event e) {
        return "/api/v1/events/" + e.getId() + "/waitlist";
    }

    private String body(UUID tierId, Integer addTickets) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("tierId", tierId);
        m.put("addTickets", addTickets);
        return om.writeValueAsString(m);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(User u) {
        Authentication auth = new UsernamePasswordAuthenticationToken(fx.principal(u), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
        return authentication(auth);
    }
}
