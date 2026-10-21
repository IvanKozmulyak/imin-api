package com.imin.iminapi.referral;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.referral.dto.PutReferralPerkRequest;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import com.imin.iminapi.util.OrderNumber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The organizer's crew-link leaderboard: totals, money, org scoping and the "given" mark. */
@IminIntegrationTest
class ReferralBoardTest {

    @Autowired MockMvc mvc;
    @Autowired ReferralPerkService perkService;
    @Autowired ReferralPerkUnlockRepository unlocks;
    @Autowired ReferralProperties referralProps;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired AuditRows auditRows;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final List<UUID> orgIds = new ArrayList<>();
    private Organization org;
    private User user;
    private AuthPrincipal owner;
    private Event event;

    @BeforeEach
    void setUp() {
        flips.set(referralProps, "perksEnabled", true);
        org = fx.org();
        orgIds.add(org.getId());
        user = fx.owner(org);
        owner = fx.principal(user);
        event = fx.event(org, user, EventStatus.LIVE, Instant.now().plus(10, ChronoUnit.DAYS));
        event.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        event = events.save(event);
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void totalsMatchTheRowsAndNetRevenueExcludesRefundsAndLostChargebacks() throws Exception {
        Order a = referrer(false);
        Order a1 = friend(a, 3000, false, Ticket.STATE_ISSUED, Ticket.STATE_ISSUED);
        refund(a1, 500, "SUCCEEDED");
        refund(a1, 700, "PENDING");
        Order a2 = friend(a, 1500, false, Ticket.STATE_REVOKED);
        lostDispute(a2, 1500);
        Order b = referrer(false);
        friend(b, 1500, false, Ticket.STATE_ISSUED);
        Order testModeReferrer = referrer(true);
        friend(testModeReferrer, 9999, true, Ticket.STATE_ISSUED);

        JsonNode board = getBoard(event.getId(), owner, null);

        // 3000 - 500 SUCCEEDED (the PENDING 700 stays) + (1500 - 1500 LOST) + 1500 = 4000.
        assertThat(board.path("totals").path("netRevenueMinor").asLong()).isEqualTo(4000L);
        assertThat(board.path("rows")).extracting(r -> r.path("orderId").asText())
                .containsExactly(a.getId().toString(), b.getId().toString());
        assertThat(board.path("rows")).extracting(r -> r.path("tickets").asLong()).containsExactly(2L, 1L);
        long rowSum = 0;
        for (JsonNode r : board.path("rows")) rowSum += r.path("tickets").asLong();
        assertThat(board.path("totals").path("ticketsViaLinks").asLong()).isEqualTo(rowSum).isEqualTo(3L);
        assertThat(board.path("totals").path("referrers").asLong()).isEqualTo(2L);
        assertThat(board.path("total").asLong()).isEqualTo(2L);
        assertThat(board.path("rows").get(0).path("orderNumber").asText()).isEqualTo(OrderNumber.code(a.getId()));
        assertThat(board.path("rows").get(0).path("email").asText()).isEqualTo(a.getEmail());

        JsonNode capped = getBoard(event.getId(), owner, 1);
        assertThat(capped.path("rows")).hasSize(1);
        assertThat(capped.path("total").asLong()).isEqualTo(2L);
        assertThat(capped.path("totals").path("ticketsViaLinks").asLong()).isEqualTo(3L);
    }

    @Test
    void markGivenThenUndoRestoresUnlocked() throws Exception {
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 1));
        Order a = referrer(false);
        friend(a, 1500, false, Ticket.STATE_ISSUED);
        perkService.evaluate(a.getId());
        Instant unlockedAt = unlocks.findById(a.getId()).orElseThrow().getUnlockedAt();
        String path = "/api/v1/events/" + event.getId() + "/referrals/" + a.getId() + "/perk-given";

        JsonNode given = read(mvc.perform(as(post(path), owner)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(given.path("result").asText()).isEqualTo("given");
        assertThat(given.path("perk").path("label").asText()).isEqualTo("Skip the line");
        assertThat(given.path("perk").path("givenAt").isNull()).isFalse();
        auditRows.assertRecorded(org.getId(), AuditActions.REFERRAL_PERK_GIVEN, "order", a.getId());
        JsonNode again = read(mvc.perform(as(post(path), owner)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(again.path("result").asText()).isEqualTo("already_given");
        String givenAt = given.path("perk").path("givenAt").asText();

        mvc.perform(as(delete(path).param("givenAt", givenAt), owner)).andExpect(status().isNoContent());

        ReferralPerkUnlock after = unlocks.findById(a.getId()).orElseThrow();
        assertThat(after.getGivenAt()).isNull();
        assertThat(after.getGivenVia()).isNull();
        assertThat(after.getUnlockedAt()).isEqualTo(unlockedAt);
        auditRows.assertRecorded(org.getId(), AuditActions.REFERRAL_PERK_GIVEN_UNDONE, "order", a.getId());
        JsonNode row = getBoard(event.getId(), owner, null).path("rows").get(0);
        assertThat(row.path("unlockedAt").isNull()).isFalse();
        assertThat(row.path("givenAt").isNull()).isTrue();
        // Undoing an undone mark is a no-op.
        mvc.perform(as(delete(path).param("givenAt", givenAt), owner)).andExpect(status().isNoContent());

        // Given again since: the first mark's Undo is stale and must not clear the new one.
        JsonNode regiven = read(mvc.perform(as(post(path), owner)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(regiven.path("result").asText()).isEqualTo("given");
        mvc.perform(as(delete(path).param("givenAt", givenAt), owner)).andExpect(status().isConflict());
        assertThat(unlocks.findById(a.getId()).orElseThrow().getGivenAt())
                .isEqualTo(Instant.parse(regiven.path("perk").path("givenAt").asText()));
    }

    @Test
    void aReferrerWhoseFriendsWereAllRefundedIsListedOnlyWithAnUnlock_showingWhatTheyWerePromised() throws Exception {
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 2));
        Order unlocked = referrer(false);
        Order unlockedFriend = friend(unlocked, 3000, false, Ticket.STATE_ISSUED, Ticket.STATE_ISSUED);
        perkService.evaluate(unlocked.getId());
        jdbc.update("UPDATE tickets SET state = 'refunded' WHERE order_id = ?", unlockedFriend.getId());
        Order dropped = referrer(false);
        friend(dropped, 1500, false, Ticket.STATE_REFUNDED);
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Free coat check", 5));

        JsonNode board = getBoard(event.getId(), owner, null);

        assertThat(board.path("rows")).extracting(r -> r.path("orderId").asText())
                .containsExactly(unlocked.getId().toString());
        JsonNode row = board.path("rows").get(0);
        assertThat(row.path("tickets").asLong()).isZero();
        assertThat(row.path("unlockLabel").asText()).isEqualTo("Skip the line");
        assertThat(row.path("unlockThreshold").asInt()).isEqualTo(2);
        assertThat(board.path("total").asLong()).isEqualTo(1L);
        assertThat(board.path("totals").path("referrers").asLong()).isEqualTo(1L);
    }

    @Test
    void oneBuyerWithTwoOrdersIsOneReferrerOnTwoRows() throws Exception {
        String email = fx.email("twice");
        Order first = fx.order(event, email);
        fx.ticket(first, Ticket.STATE_ISSUED);
        Order second = fx.order(event, email.toUpperCase());
        fx.ticket(second, Ticket.STATE_ISSUED);
        friend(first, 1500, false, Ticket.STATE_ISSUED);
        friend(second, 1500, false, Ticket.STATE_ISSUED);

        JsonNode board = getBoard(event.getId(), owner, null);

        assertThat(board.path("rows")).hasSize(2);
        assertThat(board.path("total").asLong()).isEqualTo(2L);
        assertThat(board.path("totals").path("referrers").asLong()).isEqualTo(1L);
        assertThat(board.path("totals").path("ticketsViaLinks").asLong()).isEqualTo(2L);
    }

    @Test
    void withPerksOffTheDashboardCanNeitherMarkNorUndo() throws Exception {
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 1));
        Order locked = referrer(false);
        friend(locked, 1500, false, Ticket.STATE_ISSUED);
        Order given = referrer(false);
        friend(given, 1500, false, Ticket.STATE_ISSUED);
        perkService.evaluateEvent(event.getId());
        String givenPath = "/api/v1/events/" + event.getId() + "/referrals/" + given.getId() + "/perk-given";
        String givenAt = read(mvc.perform(as(post(givenPath), owner)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).path("perk").path("givenAt").asText();
        flips.set(referralProps, "perksEnabled", false);

        mvc.perform(as(post("/api/v1/events/" + event.getId() + "/referrals/" + locked.getId() + "/perk-given"), owner))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete(givenPath).param("givenAt", givenAt), owner)).andExpect(status().isNotFound());

        assertThat(unlocks.findById(locked.getId()).orElseThrow().getGivenAt()).isNull();
        assertThat(unlocks.findById(given.getId()).orElseThrow().getGivenAt()).isEqualTo(Instant.parse(givenAt));
    }

    @Test
    void anotherOrgsEventIs404() throws Exception {
        Organization other = fx.org();
        orgIds.add(other.getId());
        AuthPrincipal stranger = fx.principal(fx.owner(other));
        Order a = referrer(false);
        friend(a, 1500, false, Ticket.STATE_ISSUED);
        String base = "/api/v1/events/" + event.getId();

        mvc.perform(as(get(base + "/referrals"), stranger)).andExpect(status().isNotFound());
        mvc.perform(as(put(base + "/referral-perk").contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Skip the line\",\"threshold\":2}"), stranger)).andExpect(status().isNotFound());
        mvc.perform(as(post(base + "/referrals/" + a.getId() + "/perk-given"), stranger))
                .andExpect(status().isNotFound());
    }

    private JsonNode getBoard(UUID eventId, AuthPrincipal principal, Integer limit) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/v1/events/" + eventId + "/referrals");
        if (limit != null) req = req.param("limit", String.valueOf(limit));
        return read(mvc.perform(as(req, principal)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, AuthPrincipal principal) {
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        return req.with(auth);
    }

    private JsonNode read(String body) throws Exception {
        return json.readTree(body);
    }

    private Order referrer(boolean testMode) {
        Order o = fx.order(event, fx.email("referrer"));
        if (testMode) {
            o.setTestMode(true);
            o = orders.save(o);
        }
        fx.ticket(o, Ticket.STATE_ISSUED);
        return o;
    }

    private Order friend(Order referrer, long totalMinor, boolean testMode, String... ticketStates) {
        Order o = new Order();
        o.setToken("ORD_" + UUID.randomUUID());
        o.setEventId(event.getId());
        o.setOrgId(event.getOrgId());
        o.setEmail(fx.email("friend"));
        o.setTotalMinor(totalMinor);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setTestMode(testMode);
        o.setReferredByOrderId(referrer.getId());
        o = orders.save(o);
        for (String state : ticketStates) fx.ticket(o, state);
        return o;
    }

    private void refund(Order o, long amountMinor, String status) {
        jdbc.update("""
                INSERT INTO refunds (id, order_id, stripe_payment_intent_id, amount_minor, currency,
                    application_fee_refund_minor, reason, status, initiated_by_user_id, idempotency_key)
                VALUES (?, ?, 'pi_x', ?, 'EUR', 0, 'OTHER', ?, ?, ?)
                """, UUID.randomUUID(), o.getId(), amountMinor, status, user.getId(), "k-" + UUID.randomUUID());
    }

    private void lostDispute(Order o, long amountMinor) {
        jdbc.update("""
                INSERT INTO disputes (id, stripe_dispute_id, org_id, event_id, order_id, amount_minor, currency,
                    status, test_mode, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'eur', 'lost', false, now(), now())
                """, UUID.randomUUID(), "du_" + UUID.randomUUID().toString().substring(0, 12), org.getId(),
                event.getId(), o.getId(), amountMinor);
    }
}
