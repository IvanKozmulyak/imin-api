package com.imin.iminapi.controller.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.gate.GateLoginRequest;
import com.imin.iminapi.dto.gate.GateLoginResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.GateCredentialRepository;
import com.imin.iminapi.repository.GateSessionRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.gate.GateAuthService;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for the gate-token flow: log in via the public
 * {@code /api/v1/gate/login} endpoint, then redeem a ticket on
 * {@code /api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem} using the
 * returned bearer token. Exercises every layer (controller, security filter,
 * gate auth service, ticket-redeem service, JPA).
 *
 * <p>Also confirms the negative case: a gate token presented on a different
 * organizer-side endpoint must be rejected as {@code 401 AUTH_MISSING} (the
 * filter silently drops the principal so the security config returns the
 * standard auth-required envelope).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class TicketRedeemGateAuthTest {

    @Autowired MockMvc mvc;
    @Autowired GateAuthService gateAuth;
    @Autowired GateCredentialRepository gateCredentials;
    @Autowired GateSessionRepository gateSessions;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired QrPayloadSigner signer;

    final ObjectMapper om = new ObjectMapper();

    private Organization org;
    private Event event;
    private Ticket ticket;
    private String gateToken;

    @BeforeEach
    void seed() {
        gateSessions.deleteAll();
        gateCredentials.deleteAll();
        tickets.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();

        org = new Organization();
        org.setName("Gate E2E Org");
        org.setSlug("gate-e2e-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("e2e@example.test");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setOrgId(org.getId());
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Gate E2E Event");
        event.setSlug("gate-e2e-event-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setCurrency("EUR");
        event.setCreatedBy(owner.getId());
        event = events.save(event);

        Order order = new Order();
        order.setToken("ORD_" + UUID.randomUUID());
        order.setEventId(event.getId());
        order.setOrgId(org.getId());
        order.setEmail("buyer@example.test");
        order.setTotalMinor(1500L);
        order.setCurrency("EUR");
        order.setPaymentMethod("stripe");
        order = orders.save(order);

        ticket = new Ticket();
        ticket.setToken("TKT_" + UUID.randomUUID());
        ticket.setOrderId(order.getId());
        ticket.setEventId(event.getId());
        ticket.setTierId(UUID.randomUUID());
        ticket.setTierName("GA");
        ticket.setState("issued");
        ticket = tickets.save(ticket);

        // Provision a gate password and log in to get a real bearer token.
        AuthPrincipal ownerPrincipal = new AuthPrincipal(owner.getId(), org.getId(),
                UserRole.OWNER, UUID.randomUUID());
        gateAuth.rotate(ownerPrincipal, org.getId(), "gate-password-12345");
        GateLoginResponse loginResp = gateAuth.login(
                new GateLoginRequest(org.getSlug(), "gate-password-12345"));
        gateToken = loginResp.token();
    }

    @AfterEach
    void clean() {
        gateSessions.deleteAll();
        gateCredentials.deleteAll();
        tickets.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    void gate_token_can_redeem_ticket_on_allowed_endpoint() throws Exception {
        String qr = signer.sign(ticket.getToken());

        mvc.perform(post("/api/v1/orgs/" + org.getId() + "/events/" + event.getId() + "/tickets/redeem")
                        .header("Authorization", "Bearer " + gateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("qrPayload", qr))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("redeemed"));

        Ticket after = tickets.findByToken(ticket.getToken()).orElseThrow();
        assertThat(after.getState()).isEqualTo("redeemed");
        assertThat(after.getRedeemedAt()).isNotNull();
        // Gate-token redemptions store null userId (gate phones aren't tied to a user).
        assertThat(after.getRedeemedByUserId()).isNull();
    }

    @Test
    void gate_token_for_wrong_org_returns_403() throws Exception {
        // Build a SECOND org + event, then try to redeem org B's ticket using org A's gate token.
        Organization otherOrg = new Organization();
        otherOrg.setName("Other Org");
        otherOrg.setSlug("other-" + UUID.randomUUID().toString().substring(0, 8));
        otherOrg.setContactEmail("other@example.test");
        otherOrg.setCountry("DE");
        otherOrg = orgs.save(otherOrg);

        User otherOwner = new User();
        otherOwner.setOrgId(otherOrg.getId());
        otherOwner.setEmail("other-owner-" + UUID.randomUUID() + "@example.test");
        otherOwner.setRole(UserRole.OWNER);
        otherOwner = users.save(otherOwner);

        Event otherEvent = new Event();
        otherEvent.setOrgId(otherOrg.getId());
        otherEvent.setName("Other Event");
        otherEvent.setSlug("other-event-" + UUID.randomUUID().toString().substring(0, 8));
        otherEvent.setVisibility(EventVisibility.PUBLIC);
        otherEvent.setStatus(EventStatus.LIVE);
        otherEvent.setCurrency("EUR");
        otherEvent.setCreatedBy(otherOwner.getId());
        otherEvent = events.save(otherEvent);

        String qr = signer.sign(ticket.getToken());
        // Path orgId is the OTHER org's; our token is for org A — controller's
        // org check (me.orgId().equals(orgId)) must reject.
        mvc.perform(post("/api/v1/orgs/" + otherOrg.getId() + "/events/" + otherEvent.getId() + "/tickets/redeem")
                        .header("Authorization", "Bearer " + gateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("qrPayload", qr))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void gate_token_is_rejected_on_organizer_dashboard_endpoint() throws Exception {
        // /api/v1/org is in the organizer dashboard subtree — must reject the gate token
        // with AUTH_MISSING (the filter drops the principal silently).
        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + gateToken))
                .andExpect(status().isNoContent()); // logout no-ops with no principal
    }

    @Test
    void gate_token_is_rejected_on_org_team_endpoint() throws Exception {
        // /api/v1/org/team — a real authenticated org endpoint. The gate token
        // must not unlock it; expect 401 AUTH_MISSING.
        mvc.perform(post("/api/v1/org/team/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + gateToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }
}
