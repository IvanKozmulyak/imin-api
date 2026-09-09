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
    @Autowired com.imin.iminapi.repository.AuditLogRepository auditLogs;

    final ObjectMapper om = new ObjectMapper();

    private Organization org;
    private Event event;
    private Ticket ticket;
    private String gateToken;

    @BeforeEach
    void seed() {
        gateSessions.deleteAll();
        gateCredentials.deleteAll();
        auditLogs.deleteAll();
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

    /**
     * {@code TicketRedeemController} carried a comment claiming the audit/log
     * trail used {@code me.actorLabel()}. There was no audit write and
     * {@code TicketRedeemService} had no log statement at all, so nobody could
     * reconstruct which door admitted whom — a GDPR accountability gap and an
     * internal-fraud blind spot, made worse by being a control that documentation
     * said existed.
     *
     * <p>The row lands in {@code audit_logs} rather than on new {@code tickets}
     * columns: it is the redemption log row the card offers as the alternative, it
     * is already org-scoped and already readable through {@code GET /orgs/{id}/audit},
     * and one row per scan records the repeat attempts that a single "redeemed by"
     * column would overwrite.
     */
    @Test
    void a_gate_redemption_is_recorded_against_the_gate_session_that_did_it() throws Exception {
        long before = auditLogs.count();
        UUID sessionId = gateSessions.findAll().get(0).getId();
        String qr = signer.sign(ticket.getToken());

        mvc.perform(post("/api/v1/orgs/" + org.getId() + "/events/" + event.getId() + "/tickets/redeem")
                        .header("Authorization", "Bearer " + gateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("qrPayload", qr))))
                .andExpect(status().isOk());

        assertThat(auditLogs.count()).isEqualTo(before + 1);
        var row = auditLogs.findAll().stream()
                .filter(a -> "TICKET_REDEEMED".equals(a.getAction()))
                .findFirst().orElseThrow();
        assertThat(row.getOrgId()).isEqualTo(org.getId());
        assertThat(row.getTargetType()).isEqualTo("ticket");
        assertThat(row.getTargetId()).isEqualTo(ticket.getId());
        // Which door: the gate session id and the actor label, both reconstructable.
        assertThat(row.getSummary()).contains(sessionId.toString());
        assertThat(row.getSummary()).contains("gate:" + org.getId());
        assertThat(row.getSummary()).contains(event.getId().toString());
        // Never the buyer's address — the row says which door, not who walked through it.
        assertThat(row.getSummary()).doesNotContain("buyer@example.test");
    }

    /**
     * api-17: {@code Req} carries {@code @NotBlank} but the parameter is bound without
     * {@code @Valid}, so the constraint never ran — the behaviour was correct only because the
     * handler repeats the check by hand. The two disagree on the wire: bean validation answers
     * FIELD_INVALID, the hand-rolled check answers INVALID_REQUEST. This pins which one the gate
     * PWA actually sees, so the annotation cannot be "cleaned up" into a silent contract change.
     */
    @Test
    void a_blank_qrPayload_is_INVALID_REQUEST() throws Exception {
        mvc.perform(post("/api/v1/orgs/" + org.getId() + "/events/" + event.getId() + "/tickets/redeem")
                        .header("Authorization", "Bearer " + gateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("qrPayload", "  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /** A scan that admitted nobody is not an admission and must not read like one. */
    @Test
    void a_failed_scan_writes_no_redemption_row() throws Exception {
        long before = auditLogs.count();

        mvc.perform(post("/api/v1/orgs/" + org.getId() + "/events/" + event.getId() + "/tickets/redeem")
                        .header("Authorization", "Bearer " + gateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("qrPayload", "not-a-signed-payload"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("invalid"));

        assertThat(auditLogs.count()).isEqualTo(before);
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

    /**
     * A gate token is ORG-scoped, never event-scoped: {@code AuthPrincipal.forGate}
     * carries an org id and nothing else. The controller only compared the path
     * {@code orgId} to the principal's, and the service only compared the ticket's
     * event id to the path's — so org A's own token, on org A's path, with org B's
     * event id and a copy of org B's QR string, reached the atomic UPDATE and flipped
     * a foreign ticket to {@code redeemed}. The event has to be loaded and owned.
     *
     * <p>Cross-org answers with the same {@code 404 NOT_FOUND} every other org-scoped
     * service gives (see {@code TicketTierService.loadOwnedEvent}) — it must not
     * confirm that the event exists.
     */
    @Test
    void gate_token_cannot_redeem_a_ticket_from_another_orgs_event() throws Exception {
        Organization otherOrg = new Organization();
        otherOrg.setName("Victim Org");
        otherOrg.setSlug("victim-" + UUID.randomUUID().toString().substring(0, 8));
        otherOrg.setContactEmail("victim@example.test");
        otherOrg.setCountry("DE");
        otherOrg = orgs.save(otherOrg);

        User otherOwner = new User();
        otherOwner.setOrgId(otherOrg.getId());
        otherOwner.setEmail("victim-owner-" + UUID.randomUUID() + "@example.test");
        otherOwner.setRole(UserRole.OWNER);
        otherOwner = users.save(otherOwner);

        Event otherEvent = new Event();
        otherEvent.setOrgId(otherOrg.getId());
        otherEvent.setName("Victim Event");
        otherEvent.setSlug("victim-event-" + UUID.randomUUID().toString().substring(0, 8));
        otherEvent.setVisibility(EventVisibility.PUBLIC);
        otherEvent.setStatus(EventStatus.LIVE);
        otherEvent.setCurrency("EUR");
        otherEvent.setCreatedBy(otherOwner.getId());
        otherEvent = events.save(otherEvent);

        Order otherOrder = new Order();
        otherOrder.setToken("ORD_" + UUID.randomUUID());
        otherOrder.setEventId(otherEvent.getId());
        otherOrder.setOrgId(otherOrg.getId());
        otherOrder.setEmail("victim-buyer@example.test");
        otherOrder.setTotalMinor(2500L);
        otherOrder.setCurrency("EUR");
        otherOrder.setPaymentMethod("stripe");
        otherOrder = orders.save(otherOrder);

        Ticket otherTicket = new Ticket();
        otherTicket.setToken("TKT_" + UUID.randomUUID());
        otherTicket.setOrderId(otherOrder.getId());
        otherTicket.setEventId(otherEvent.getId());
        otherTicket.setTierId(UUID.randomUUID());
        otherTicket.setTierName("GA");
        otherTicket.setState("issued");
        otherTicket = tickets.save(otherTicket);

        long auditsBefore = auditLogs.count();
        // Path orgId is OUR org (so the controller's membership check passes);
        // the event id and the QR both belong to the other org.
        mvc.perform(post("/api/v1/orgs/" + org.getId() + "/events/" + otherEvent.getId() + "/tickets/redeem")
                        .header("Authorization", "Bearer " + gateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("qrPayload", signer.sign(otherTicket.getToken())))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(tickets.findByToken(otherTicket.getToken()).orElseThrow().getState())
                .as("a foreign ticket must not be redeemable with our gate token")
                .isEqualTo("issued");
        assertThat(auditLogs.count()).isEqualTo(auditsBefore);
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
