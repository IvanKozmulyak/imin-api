package com.imin.iminapi.stripe;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.event.InventoryService;
import com.imin.iminapi.service.ticket.PaidCheckoutService;
import com.imin.iminapi.settlement.SettlementRepository;
import com.stripe.StripeClient;
import com.stripe.net.ApiRequest;
import com.stripe.net.ApiResource;
import com.stripe.net.StripeResponseGetter;
import com.stripe.net.Webhook;
import com.stripe.service.ChargeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a chargeback actually does, driven end-to-end through the V1 webhook against H2 +
 * Flyway: the {@code disputes} registry row, the buyer's tickets, and the out-of-order guard.
 *
 * <p>The settlements read-model half of {@code charge.dispute.*} is covered by
 * {@link SettlementIngestWebhookTest}; this class is about the consequences that move money
 * and revoke access. Stripe is faked at the {@link StripeResponseGetter} seam because a
 * dispute payload carries {@code "charge": "ch_..."} as a plain string, so the ingest has to
 * retrieve the charge to reach the PaymentIntent that identifies the order.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class SettlementIngestServiceTest {

    private static final String SECRET = "whsec_test_dispute_secret";

    @Autowired StripeWebhookService webhook;
    @Autowired StripeProperties props;
    @Autowired DisputeRepository disputes;
    @Autowired SettlementRepository settlements;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean StripeClient stripeClient;
    @MockitoBean PaidCheckoutService paidCheckoutService;
    @MockitoBean InventoryService inventoryService;
    /** The organizer alert fires AFTER_COMMIT; mocked so no test ever reaches Resend. */
    @MockitoBean EmailService email;

    private Organization org;
    private String acctId;
    private Event event;

    @BeforeEach
    void setUp() {
        wipe();
        props.setWebhookSecretV1(SECRET);

        acctId = "acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Organization o = new Organization();
        o.setName("Dispute Org");
        o.setSlug("dispute-org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("payouts@test.example");
        o.setCountry("DE");
        o.setStripeAccountId(acctId);
        org = orgs.save(o);

        User u = new User();
        u.setOrgId(org.getId());
        u.setEmail("creator-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        UUID userId = users.save(u).getId();

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Disputed Night");
        e.setSlug("disputed-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.LIVE);
        e.setCurrency("EUR");
        e.setEndsAt(Instant.now().plus(10, ChronoUnit.DAYS));
        e.setCreatedBy(userId);
        event = events.save(e);
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private void wipe() {
        disputes.deleteAll();
        settlements.deleteAll();
        jdbc.update("DELETE FROM processed_webhook_events");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM users");
        orgs.deleteAll();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String sign(String body) throws Exception {
        long ts = Instant.now().getEpochSecond();
        String sig = Webhook.Util.computeHmacSha256(SECRET, ts + "." + body);
        return "t=" + ts + ",v1=" + sig;
    }

    /** A {@code charge.dispute.*} envelope; {@code charge} is a plain id, as Stripe sends it. */
    private String disputeEvent(String eventId, String disputeId, String chargeId, long amount,
                                String type, String status, long createdAt) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "%s",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "account": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "dispute",
                  "amount": %d,
                  "currency": "eur",
                  "reason": "fraudulent",
                  "status": "%s",
                  "charge": "%s"
                }
              }
            }
            """.formatted(eventId, type, createdAt, acctId, disputeId, amount, status, chargeId);
    }

    /** {@code charges().retrieve} answers with a platform destination charge naming the PI. */
    private void stubChargeRetrieve(String chargeId, String paymentIntentId) throws Exception {
        StripeResponseGetter rg = mock(StripeResponseGetter.class);
        when(rg.request(any(ApiRequest.class), any(Type.class)))
                .thenAnswer(inv -> {
                    String json = """
                        { "id": "%s", "object": "charge", "amount": 4200, "currency": "eur",
                          "payment_intent": "%s", "transfer": "tr_dispute_backing",
                          "transfer_data": { "destination": "%s" } }
                        """.formatted(chargeId, paymentIntentId, acctId);
                    return ApiResource.GSON.fromJson(json, com.stripe.model.Charge.class);
                });
        when(stripeClient.charges()).thenReturn(new ChargeService(rg));
    }

    private Order paidOrder(String paymentIntentId, int ticketCount) {
        Order o = new Order();
        o.setToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(4200);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(210);
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId(paymentIntentId);
        Order saved = orders.save(o);
        for (int i = 0; i < ticketCount; i++) {
            Ticket t = new Ticket();
            t.setToken("tkt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            t.setOrderId(saved.getId());
            t.setEventId(event.getId());
            t.setTierId(UUID.randomUUID());
            t.setTierName("GA");
            t.setPriceMinor(2100);
            t.setState(Ticket.STATE_ISSUED);
            tickets.save(t);
        }
        return saved;
    }

    // ── the consequences ───────────────────────────────────────────────────────

    @Test
    void disputeCreatedRevokesTickets() throws Exception {
        String pi = "pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String disputeId = "du_" + UUID.randomUUID().toString().substring(0, 12);
        Order order = paidOrder(pi, 2);
        stubChargeRetrieve(chargeId, pi);

        String body = disputeEvent("evt_disp_revoke", disputeId, chargeId, 4200,
                "charge.dispute.created", "needs_response", Instant.now().getEpochSecond());
        webhook.handleV1Endpoint(body, sign(body));

        List<Ticket> issued = tickets.findByOrderId(order.getId());
        assertThat(issued).hasSize(2);
        assertThat(issued)
                .as("a disputed order's tickets must stop working at the door")
                .allMatch(t -> Ticket.STATE_REVOKED.equals(t.getState()));

        Dispute row = disputes.findByStripeDisputeId(disputeId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(row.getOrgId()).isEqualTo(org.getId());
        assertThat(row.getEventId()).isEqualTo(event.getId());
        assertThat(row.getOrderId()).isEqualTo(order.getId());
        assertThat(row.getStripePaymentIntentId()).isEqualTo(pi);
        assertThat(row.getStripeChargeId()).isEqualTo(chargeId);
        assertThat(row.getAmountMinor()).isEqualTo(4200L);
        assertThat(row.getCurrency()).isEqualTo("eur");
        assertThat(row.getOpenedAt()).isNotNull();
        assertThat(row.getClosedAt()).isNull();
        assertThat(disputes.countOpenByOrgId(org.getId()))
                .as("an open dispute freezes the org's payouts")
                .isEqualTo(1L);
    }

    @Test
    void disputeWonRestoresTickets() throws Exception {
        String pi = "pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String disputeId = "du_" + UUID.randomUUID().toString().substring(0, 12);
        Order order = paidOrder(pi, 2);
        stubChargeRetrieve(chargeId, pi);

        long t0 = Instant.now().getEpochSecond();
        String created = disputeEvent("evt_won_open", disputeId, chargeId, 4200,
                "charge.dispute.created", "needs_response", t0);
        webhook.handleV1Endpoint(created, sign(created));

        String won = disputeEvent("evt_won_close", disputeId, chargeId, 4200,
                "charge.dispute.closed", "won", t0 + 60);
        webhook.handleV1Endpoint(won, sign(won));

        assertThat(tickets.findByOrderId(order.getId()))
                .as("a won dispute gives the buyer their tickets back")
                .allMatch(t -> Ticket.STATE_ISSUED.equals(t.getState()));

        Dispute row = disputes.findByStripeDisputeId(disputeId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(row.getClosedAt()).isNotNull();
        assertThat(disputes.countOpenByOrgId(org.getId()))
                .as("a closed dispute stops blocking payouts")
                .isZero();
        assertThat(disputes.sumOpenOrLostMinorByEventId(event.getId()))
                .as("a win adds the face value back by leaving the open/lost sum")
                .isZero();
    }

    @Test
    void disputeCreatedWithNoResolvableOrderStillPersistsTheRow() throws Exception {
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String disputeId = "du_" + UUID.randomUUID().toString().substring(0, 12);
        // The charge names a PaymentIntent no order was ever written for.
        stubChargeRetrieve(chargeId, "pi_orphan_" + UUID.randomUUID().toString().substring(0, 8));

        String body = disputeEvent("evt_disp_orphan_row", disputeId, chargeId, 4200,
                "charge.dispute.created", "needs_response", Instant.now().getEpochSecond());
        webhook.handleV1Endpoint(body, sign(body));

        Dispute row = disputes.findByStripeDisputeId(disputeId).orElseThrow();
        assertThat(row.getOrgId())
                .as("org still resolves from the charge's transfer destination")
                .isEqualTo(org.getId());
        assertThat(row.getOrderId()).isNull();
        assertThat(row.getEventId()).isNull();
        assertThat(disputes.countOpenByOrgId(org.getId()))
                .as("an unattributable dispute still freezes the org — the money is still at risk")
                .isEqualTo(1L);
    }

    @Test
    void refundedTicketIsNotRevokedByADispute() throws Exception {
        String pi = "pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String disputeId = "du_" + UUID.randomUUID().toString().substring(0, 12);
        Order order = paidOrder(pi, 2);
        List<Ticket> seeded = tickets.findByOrderId(order.getId());
        Ticket refunded = seeded.get(0);
        refunded.setState(Ticket.STATE_REFUNDED);
        tickets.save(refunded);
        stubChargeRetrieve(chargeId, pi);

        String body = disputeEvent("evt_disp_refunded", disputeId, chargeId, 4200,
                "charge.dispute.created", "needs_response", Instant.now().getEpochSecond());
        webhook.handleV1Endpoint(body, sign(body));

        List<Ticket> after = tickets.findByOrderId(order.getId());
        assertThat(after).filteredOn(t -> t.getId().equals(refunded.getId()))
                .as("a refunded ticket keeps its refunded state — its money already went back")
                .allMatch(t -> Ticket.STATE_REFUNDED.equals(t.getState()));
        assertThat(after).filteredOn(t -> !t.getId().equals(refunded.getId()))
                .allMatch(t -> Ticket.STATE_REVOKED.equals(t.getState()));
    }

    @Test
    void staleDisputeEventDoesNotRewriteState() throws Exception {
        String pi = "pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String disputeId = "du_" + UUID.randomUUID().toString().substring(0, 12);
        Order order = paidOrder(pi, 1);
        stubChargeRetrieve(chargeId, pi);

        long t0 = Instant.now().getEpochSecond();
        String created = disputeEvent("evt_stale_created", disputeId, chargeId, 4200,
                "charge.dispute.created", "needs_response", t0);
        webhook.handleV1Endpoint(created, sign(created));

        String won = disputeEvent("evt_stale_won", disputeId, chargeId, 4200,
                "charge.dispute.closed", "won", t0 + 60);
        webhook.handleV1Endpoint(won, sign(won));

        // An OLDER "created" delivered after the close (Stripe does not guarantee order,
        // and a failed handler rolls its dedup marker back so the retry arrives late).
        String lateCreated = disputeEvent("evt_stale_created_retry", disputeId, chargeId, 4200,
                "charge.dispute.created", "needs_response", t0 - 30);
        webhook.handleV1Endpoint(lateCreated, sign(lateCreated));

        Dispute row = disputes.findByStripeDisputeId(disputeId).orElseThrow();
        assertThat(row.getStatus())
                .as("an out-of-order delivery must not drag a closed dispute back to open")
                .isEqualTo(DisputeStatus.WON);
        assertThat(tickets.findByOrderId(order.getId()))
                .as("and must not re-revoke the tickets the win restored")
                .allMatch(t -> Ticket.STATE_ISSUED.equals(t.getState()));
    }
}
