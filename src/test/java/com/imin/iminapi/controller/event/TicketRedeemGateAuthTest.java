package com.imin.iminapi.controller.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.gate.GateLoginRequest;
import com.imin.iminapi.dto.gate.GateLoginResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.GateSessionRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.TokenService;
import com.imin.iminapi.service.gate.GateAuthService;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
@IminIntegrationTest
class TicketRedeemGateAuthTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired GateAuthService gateAuth;
    @Autowired GateSessionRepository gateSessions;
    @Autowired TicketRepository tickets;
    @Autowired QrPayloadSigner signer;
    @Autowired TokenService tokenService;
    @Autowired AuditRows auditRows;

    final ObjectMapper om = new ObjectMapper();

    private Organization org;
    private Event event;
    private Ticket ticket;
    private String buyerEmail;
    private String gateToken;

    @BeforeEach
    void seed() {
        org = fx.org();
        User owner = fx.owner(org);
        event = fx.event(org, owner, EventStatus.LIVE, null);
        buyerEmail = fx.email("buyer");
        ticket = fx.ticket(fx.order(event, buyerEmail), Ticket.STATE_ISSUED);

        // Provision a gate password and log in to get a real bearer token.
        gateAuth.rotate(fx.principal(owner), org.getId(), "gate-password-12345");
        GateLoginResponse loginResp = gateAuth.login(
                new GateLoginRequest(org.getSlug(), "gate-password-12345"));
        gateToken = loginResp.token();
    }

    private long redemptionRows(UUID orgId) {
        return auditRows.forOrg(orgId).stream().filter(a -> "TICKET_REDEEMED".equals(a.getAction())).count();
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
        UUID sessionId = gateSessions.findByTokenHashAndRevokedAtIsNull(tokenService.hashOf(gateToken))
                .orElseThrow().getId();
        String qr = signer.sign(ticket.getToken());

        mvc.perform(post("/api/v1/orgs/" + org.getId() + "/events/" + event.getId() + "/tickets/redeem")
                        .header("Authorization", "Bearer " + gateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("qrPayload", qr))))
                .andExpect(status().isOk());

        var row = auditRows.assertRecorded(org.getId(), "TICKET_REDEEMED", "ticket", ticket.getId());
        // Which door: the gate session id and the actor label, both reconstructable.
        assertThat(row.getSummary()).contains(sessionId.toString());
        assertThat(row.getSummary()).contains("gate:" + org.getId());
        // The only human reference to the order is its number; never the ticket id/token or event id.
        assertThat(row.getSummary()).contains(com.imin.iminapi.util.OrderNumber.display(ticket.getOrderId()));
        assertThat(row.getSummary()).doesNotContain(ticket.getId().toString())
                .doesNotContain(ticket.getToken()).doesNotContain(event.getId().toString());
        // Never the buyer's address — the row says which door, not who walked through it.
        assertThat(row.getSummary()).doesNotContain(buyerEmail);
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
        mvc.perform(post("/api/v1/orgs/" + org.getId() + "/events/" + event.getId() + "/tickets/redeem")
                        .header("Authorization", "Bearer " + gateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("qrPayload", "not-a-signed-payload"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("invalid"));

        assertThat(redemptionRows(org.getId())).isZero();
    }

    @Test
    void gate_token_for_wrong_org_returns_403() throws Exception {
        // Build a SECOND org + event, then try to redeem org B's ticket using org A's gate token.
        Organization otherOrg = fx.org();
        Event otherEvent = fx.event(otherOrg, fx.owner(otherOrg), EventStatus.LIVE, null);

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
        Organization otherOrg = fx.org();
        Event otherEvent = fx.event(otherOrg, fx.owner(otherOrg), EventStatus.LIVE, null);
        Ticket otherTicket = fx.ticket(fx.order(otherEvent, fx.email("victim-buyer")), Ticket.STATE_ISSUED);

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
        assertThat(redemptionRows(org.getId())).isZero();
        assertThat(redemptionRows(otherOrg.getId())).isZero();
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
