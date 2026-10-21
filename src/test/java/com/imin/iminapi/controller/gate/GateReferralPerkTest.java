package com.imin.iminapi.controller.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.gate.GateLoginRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.referral.EventReferralPerkRepository;
import com.imin.iminapi.referral.ReferralPerkUnlockRepository;
import com.imin.iminapi.referral.ReferralProperties;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.gate.GateAuthService;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The door: a scan shows an unlocked crew perk, and staff mark it given exactly once, in their own org only. */
@IminIntegrationTest
class GateReferralPerkTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired GateAuthService gateAuth;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired QrPayloadSigner signer;
    @Autowired EventReferralPerkRepository perks;
    @Autowired ReferralPerkUnlockRepository unlocks;
    @Autowired ReferralProperties referralProps;
    @Autowired PropertyFlips flips;
    @Autowired AuditRows auditRows;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private Organization org;
    private User owner;
    private Event event;
    private String gateToken;

    @BeforeEach
    void setUp() {
        flips.set(referralProps, "perksEnabled", true);
        org = fx.org();
        owner = fx.owner(org);
        gateToken = gateTokenFor(org, owner);
        event = liveEvent(org, owner);
        perks.upsert(event.getId(), "Skip the line", (short) 1, Instant.now(), null);
    }

    @Test
    void redeemShowsThePerkOnlyForUnlockedOrdersAndNeverOnWrongEvent() throws Exception {
        Ticket unlockedTicket = referrerTicket(event, true);
        Ticket lockedTicket = referrerTicket(event, false);
        Event otherEvent = liveEvent(org, owner);
        perks.upsert(otherEvent.getId(), "Free coat check", (short) 1, Instant.now(), null);

        JsonNode unlocked = redeem(event, unlockedTicket);
        assertThat(unlocked.path("result").asText()).isEqualTo("redeemed");
        assertThat(unlocked.path("perk").path("label").asText()).isEqualTo("Skip the line");
        assertThat(unlocked.path("perk").path("givenAt").isNull()).isTrue();
        assertThat(redeem(event, unlockedTicket).path("perk").path("label").asText())
                .as("already_redeemed still shows it").isEqualTo("Skip the line");

        JsonNode locked = redeem(event, lockedTicket);
        assertThat(locked.path("result").asText()).isEqualTo("redeemed");
        assertThat(locked.has("perk")).isFalse();

        JsonNode wrongEvent = redeem(otherEvent, unlockedTicket);
        assertThat(wrongEvent.path("result").asText()).isEqualTo("wrong_event");
        assertThat(wrongEvent.has("perk")).isFalse();
        assertThat(wrongEvent.has("ticket")).isFalse();
    }

    @Test
    void givingTwiceAnswersAlreadyGiven() throws Exception {
        Ticket ticket = referrerTicket(event, true);

        JsonNode first = give(gateToken, event, ticket);
        assertThat(first.path("result").asText()).isEqualTo("given");
        assertThat(first.path("perk").path("label").asText()).isEqualTo("Skip the line");
        assertThat(first.path("perk").path("givenAt").isNull()).isFalse();
        assertThat(unlocks.findById(ticket.getOrderId()).orElseThrow().getGivenVia()).isEqualTo("gate");
        auditRows.assertRecorded(org.getId(), AuditActions.REFERRAL_PERK_GIVEN, "order", ticket.getOrderId());

        assertThat(give(gateToken, event, ticket).path("result").asText()).isEqualTo("already_given");
    }

    @Test
    void twoPhonesGivingAtOnceGiveItOnce() throws Exception {
        Ticket ticket = referrerTicket(event, true);
        // The unlock row exists before the race; both calls then compete for the same conditional update.
        unlocksFor(ticket);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> calls = new ArrayList<>();
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "referral_perk_unlocks", "order_id",
                ticket.getOrderId())) {
            calls.add(pool.submit(() -> give(gateToken, event, ticket).path("result").asText()));
            pause.awaitBlocked(Duration.ofSeconds(10));
            calls.add(pool.submit(() -> give(gateToken, event, ticket).path("result").asText()));
            Thread.sleep(300);
            pause.release();
            List<String> results = new ArrayList<>();
            for (Future<String> f : calls) results.add(f.get(20, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("given", "already_given");
        } finally {
            pool.shutdownNow();
        }
    }

    enum NothingToGive { REFUNDED_TICKET, CHARGED_BACK_TICKET, PERKS_SWITCHED_OFF }

    @ParameterizedTest
    @EnumSource(NothingToGive.class)
    void anUnlockedOrderGetsNothingForADeadTicketOrWithPerksOff(NothingToGive c) throws Exception {
        Ticket ticket = referrerTicket(event, true);
        unlocksFor(ticket);
        switch (c) {
            case REFUNDED_TICKET -> jdbc.update("UPDATE tickets SET state = 'refunded' WHERE id = ?", ticket.getId());
            case CHARGED_BACK_TICKET -> jdbc.update("UPDATE tickets SET state = 'revoked' WHERE id = ?", ticket.getId());
            case PERKS_SWITCHED_OFF -> flips.set(referralProps, "perksEnabled", false);
        }

        JsonNode r = give(gateToken, event, ticket);

        assertThat(r.path("result").asText()).isEqualTo(c == NothingToGive.PERKS_SWITCHED_OFF ? "not_unlocked" : "invalid");
        assertThat(r.path("perk").isNull() || r.path("perk").isMissingNode()).isTrue();
        assertThat(unlocks.findById(ticket.getOrderId()).orElseThrow().getGivenAt()).isNull();
    }

    @Test
    void aGateTokenOfAnotherOrgCannotGivePerks() throws Exception {
        Ticket ticket = referrerTicket(event, true);
        Organization other = fx.org();
        String otherToken = gateTokenFor(other, fx.owner(other));

        mvc.perform(post("/api/v1/gate/events/" + event.getId() + "/referral-perk/given")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("qrPayload", signer.sign(ticket.getToken())))))
                .andExpect(status().isNotFound());

        assertThat(unlocks.findById(ticket.getOrderId()).map(u -> u.getGivenAt())).isEmpty();
    }

    private void unlocksFor(Ticket ticket) throws Exception {
        redeem(event, ticket);
        assertThat(unlocks.findById(ticket.getOrderId())).isPresent();
    }

    private JsonNode redeem(Event ev, Ticket ticket) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/orgs/" + org.getId() + "/events/" + ev.getId() + "/tickets/redeem")
                        .header("Authorization", "Bearer " + gateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("qrPayload", signer.sign(ticket.getToken())))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode give(String token, Event ev, Ticket ticket) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/gate/events/" + ev.getId() + "/referral-perk/given")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("qrPayload", signer.sign(ticket.getToken())))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** A referrer's ticket; with {@code withFriend} one friend ticket was bought with the link (threshold 1). */
    private Ticket referrerTicket(Event ev, boolean withFriend) {
        Order referrer = fx.order(ev, fx.email("referrer"));
        Ticket t = fx.ticket(referrer, Ticket.STATE_ISSUED);
        if (withFriend) {
            Order friend = new Order();
            friend.setToken("ORD_" + UUID.randomUUID());
            friend.setEventId(ev.getId());
            friend.setOrgId(ev.getOrgId());
            friend.setEmail(fx.email("friend"));
            friend.setTotalMinor(1500L);
            friend.setCurrency("EUR");
            friend.setPaymentMethod("stripe");
            friend.setReferredByOrderId(referrer.getId());
            fx.ticket(orders.save(friend), Ticket.STATE_ISSUED);
        }
        return t;
    }

    private Event liveEvent(Organization o, User u) {
        Event e = fx.event(o, u, EventStatus.LIVE, Instant.now().plus(2, ChronoUnit.HOURS));
        e.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        return events.save(e);
    }

    private String gateTokenFor(Organization o, User u) {
        String password = "gate-password-" + UUID.randomUUID().toString().substring(0, 8);
        gateAuth.rotate(fx.principal(u), o.getId(), password);
        return gateAuth.login(new GateLoginRequest(o.getSlug(), password)).token();
    }
}
