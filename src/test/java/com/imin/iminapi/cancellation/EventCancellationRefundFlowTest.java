package com.imin.iminapi.cancellation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundReferenceGenerator;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundRequest;
import com.imin.iminapi.refund.RefundRequestReason;
import com.imin.iminapi.refund.RefundRequestRepository;
import com.imin.iminapi.refund.RefundRequestStatus;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** After a cancel, the sweeper refunds every paid order exactly what is still owed, once, and tells the buyer. */
@IminIntegrationTest
class EventCancellationRefundFlowTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StripeClient stripeClient;
    @Autowired EventCancellationSweeper sweeper;
    @Autowired RefundRepository refunds;
    @Autowired RefundRequestRepository requests;
    @Autowired RefundReferenceGenerator references;
    @Autowired DisputeRepository disputes;
    @Autowired RecordingEmailService emails;
    @Autowired MutableClock clock;
    @Autowired @Qualifier("ticketEmailExecutor") java.util.concurrent.Executor ticketEmailExecutor;
    final ObjectMapper om = new ObjectMapper();

    private final List<UUID> orgIds = new ArrayList<>();
    private AuthPrincipal principal;
    private Event event;
    private TicketTier tier;
    private com.stripe.service.RefundService stripeRefunds;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        principal = fx.principal(owner);
        event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(10)));
        tier = fx.tier(event, 1000, 100);
        jdbc.update("UPDATE ticket_tiers SET sold = 20 WHERE id = ?", tier.getId());
        stripeRefunds = mock(com.stripe.service.RefundService.class);
        when(stripeClient.refunds()).thenReturn(stripeRefunds);
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void tick_refundsEveryPaidOrderInFull_onceEach() throws Exception {
        Order en = paidOrder(2298, 298, null);
        Ticket issued = ticket(en, Ticket.STATE_ISSUED);
        Ticket redeemed = ticket(en, Ticket.STATE_REDEEMED);
        Order fr = paidOrder(1149, 149, "fr");
        Ticket frTicket = ticket(fr, Ticket.STATE_ISSUED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> stripeRefund("pending"));

        cancel();
        tick();

        Refund enRefund = onlyRefund(en);
        assertThat(enRefund.getReason()).isEqualTo(RefundReason.EVENT_CANCELLED);
        assertThat(enRefund.getAmountMinor()).isEqualTo(2298);
        assertThat(enRefund.getApplicationFeeRefundMinor()).isEqualTo(298);
        assertThat(enRefund.getInitiatedByUserId()).isEqualTo(principal.userId());
        assertThat(enRefund.getStatus()).isEqualTo(RefundStatus.PENDING);
        // Redeemed tickets are refunded too: a cancelled event is refunded in full.
        assertThat(claims(enRefund)).containsExactlyInAnyOrder(issued.getId(), redeemed.getId());
        Refund frRefund = onlyRefund(fr);
        assertThat(frRefund.getAmountMinor()).isEqualTo(1149);
        assertThat(claims(frRefund)).containsExactly(frTicket.getId());

        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefunds, times(2)).create(params.capture(), options.capture());
        RefundCreateParams enParams = params.getAllValues().stream()
                .filter(p -> en.getStripePaymentIntentId().equals(p.getPaymentIntent())).findFirst().orElseThrow();
        assertThat(enParams.getAmount()).isEqualTo(2298L);
        assertThat(enParams.getReverseTransfer()).isTrue();
        assertThat(enParams.getRefundApplicationFee()).isTrue();
        assertThat(enParams.getReason()).isNull();
        assertThat(enParams.getMetadata()).isEqualTo(Map.of("imin_refund_id", enRefund.getId().toString()));
        assertThat(item(en)).isEqualTo("triggered");
        assertThat(item(fr)).isEqualTo("triggered");

        RecordingEmailService.SentEmail frMail = mailTo(fr);
        assertThat(frMail.subject()).isEqualTo("Annulé : " + event.getName() + " · commande #"
                + fr.getId().toString().substring(0, 8));
        assertThat(frMail.text()).contains("Vous serez remboursé de 11.49 EUR pour la commande #"
                + fr.getId().toString().substring(0, 8) + " : la totalité de votre paiement");
        RecordingEmailService.SentEmail enMail = mailTo(en);
        assertThat(enMail.subject()).isEqualTo("Cancelled: " + event.getName() + " · order #"
                + en.getId().toString().substring(0, 8));
        assertThat(enMail.text()).contains("You will get back 22.98 EUR for order #"
                + en.getId().toString().substring(0, 8) + ": everything you paid, booking fee included.");

        // A buyer who paid after the cancel is enrolled and refunded once; nobody else is refunded again.
        Order late = paidOrder(1149, 149, null);
        ticket(late, Ticket.STATE_ISSUED);
        clock.advance(Duration.ofMinutes(6));
        tick();

        verify(stripeRefunds, times(3)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(onlyRefund(late).getAmountMinor()).isEqualTo(1149);
        assertThat(refundsOf(en)).hasSize(1);
        assertThat(refundsOf(fr)).hasSize(1);
        assertThat(mailsTo(late)).hasSize(1);
        assertThat(mailsTo(en)).hasSize(1);
    }

    @Test
    void tick_refundsRemainderOnly_afterEarlierPartial() throws Exception {
        Order o = paidOrder(2298, 298, null);
        ticket(o, Ticket.STATE_REFUNDED);
        ticket(o, Ticket.STATE_ISSUED);
        priorRefund(o, 1149, 149, RefundStatus.SUCCEEDED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> stripeRefund("pending"));

        cancel();
        tick();

        verify(stripeRefunds).create(argThat((RefundCreateParams p) -> p != null && p.getAmount() == 1149L),
                any(RequestOptions.class));
        Refund cancellation = refundsOf(o).stream()
                .filter(r -> r.getReason() == RefundReason.EVENT_CANCELLED).findFirst().orElseThrow();
        assertThat(cancellation.getAmountMinor()).isEqualTo(1149);
        assertThat(cancellation.getApplicationFeeRefundMinor()).isEqualTo(149);
        assertThat(mailTo(o).text()).contains("You will get back 11.49 EUR for order #"
                + o.getId().toString().substring(0, 8) + ": the part not refunded yet, booking fee included.");
    }

    @Test
    void tick_waitsForInFlightRefund_thenTopsUp() throws Exception {
        Order o = paidOrder(2298, 298, null);
        ticket(o, Ticket.STATE_ISSUED);
        ticket(o, Ticket.STATE_ISSUED);
        UUID inFlight = priorRefund(o, 1149, 149, RefundStatus.REQUESTED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> stripeRefund("pending"));

        cancel();
        tick();

        verify(stripeRefunds, never()).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(item(o)).isEqualTo("in_progress");

        // The in-flight refund settles; the next pass refunds only what is still owed.
        jdbc.update("UPDATE refunds SET status = 'SUCCEEDED' WHERE id = ?", inFlight);
        clock.advance(Duration.ofMinutes(6));
        tick();

        verify(stripeRefunds, times(1)).create(
                argThat((RefundCreateParams p) -> p != null && p.getAmount() == 1149L), any(RequestOptions.class));
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(item(o)).isEqualTo("triggered");
    }

    @Test
    void tick_disputes() throws Exception {
        Order open = paidOrder(1149, 149, null);
        ticket(open, Ticket.STATE_ISSUED);
        dispute(open, 1149, DisputeStatus.OPEN);
        Order wonAndOpen = paidOrder(1149, 149, null);
        ticket(wonAndOpen, Ticket.STATE_ISSUED);
        dispute(wonAndOpen, 1149, DisputeStatus.WON);
        Dispute second = dispute(wonAndOpen, 1149, DisputeStatus.OPEN);
        Order lost = paidOrder(1149, 149, null);
        ticket(lost, Ticket.STATE_ISSUED);
        dispute(lost, 1149, DisputeStatus.LOST);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> stripeRefund("pending"));

        cancel();
        tick();

        verify(stripeRefunds, never()).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(item(open)).isEqualTo("waiting_dispute");
        // Winning one dispute does not settle the other one still open on the same order.
        assertThat(item(wonAndOpen)).isEqualTo("waiting_dispute");
        assertThat(item(lost)).isEqualTo("dispute_lost");
        assertThat(mailTo(open).text()).contains("There is a payment dispute with your bank on order #"
                + open.getId().toString().substring(0, 8) + ", so your refund follows the bank's decision.");
        assertThat(mailTo(lost).text()).contains("payment dispute");

        // The bank sides with the merchant on the last open claim: imin refunds the buyer itself.
        jdbc.update("UPDATE disputes SET status = 'won' WHERE id = ?", second.getId());
        clock.advance(Duration.ofMinutes(6));
        tick();

        verify(stripeRefunds, times(1)).create(
                argThat((RefundCreateParams p) -> p != null
                        && wonAndOpen.getStripePaymentIntentId().equals(p.getPaymentIntent())),
                any(RequestOptions.class));
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(item(wonAndOpen)).isEqualTo("triggered");
        assertThat(item(lost)).isEqualTo("dispute_lost");
    }

    @Test
    void tick_stopsAfterThreeRefusals_andCountsNeedsAttention() throws Exception {
        Order o = paidOrder(1149, 149, null);
        ticket(o, Ticket.STATE_ISSUED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new InvalidRequestException("No such payment_intent", "payment_intent", "req_1",
                        "resource_missing", 400, null));

        cancel();
        tick();
        assertThat(attempts(o)).isEqualTo(1);
        clock.advance(Duration.ofMinutes(16));
        tick();
        assertThat(attempts(o)).isEqualTo(2);
        clock.advance(Duration.ofMinutes(31));
        tick();
        assertThat(attempts(o)).isEqualTo(3);
        assertThat(item(o)).isEqualTo("needs_attention");
        clock.advance(Duration.ofHours(2));
        tick();

        verify(stripeRefunds, times(3)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(jdbc.queryForObject("SELECT last_error FROM event_cancellation_orders WHERE order_id = ?",
                String.class, o.getId())).contains("No such payment_intent");
        cancellation().andExpect(jsonPath("$.orders.needsAttention").value(1))
                .andExpect(jsonPath("$.orders.processing").value(0));
    }

    @Test
    void tick_lostItemWrite_neverRefundsTwice() throws Exception {
        Order o = paidOrder(1149, 149, null);
        ticket(o, Ticket.STATE_ISSUED);
        AtomicReference<PgFaults.Fault> fault = new AtomicReference<>();
        // The item row is claimed before Stripe is called; break its write right after Stripe answers.
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            fault.set(PgFaults.failWrites(jdbc, "event_cancellation_orders", "order_id", o.getId()));
            return stripeRefund("pending");
        });

        cancel();
        try {
            tick();
        } finally {
            if (fault.get() != null) fault.get().close();
        }
        assertThat(item(o)).isEqualTo("pending");
        assertThat(refundsOf(o)).hasSize(1);

        clock.advance(Duration.ofMinutes(6));
        tick();

        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(refundsOf(o)).hasSize(1);
        assertThat(item(o)).isEqualTo("triggered");
    }

    @Test
    void tick_closesPendingRequest_andRejectIs409() throws Exception {
        Order refunded = paidOrder(1149, 149, null);
        ticket(refunded, Ticket.STATE_ISSUED);
        RefundRequest closed = pendingRequest(refunded);
        Order disputed = paidOrder(1149, 149, null);
        ticket(disputed, Ticket.STATE_ISSUED);
        dispute(disputed, 1149, DisputeStatus.OPEN);
        RefundRequest stillPending = pendingRequest(disputed);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> stripeRefund("pending"));

        cancel();

        // imin refunds a cancelled event's orders; the organizer can no longer turn a buyer down.
        int before = emails.sent().size();
        mvc.perform(post("/api/v1/orgs/{orgId}/refund-requests/{id}/reject", stillPending.getOrgId(), stillPending.getId())
                        .with(auth(principal)).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"no\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        mvc.perform(post("/api/v1/orgs/{orgId}/refund-requests/{id}/approve", stillPending.getOrgId(), stillPending.getId())
                        .with(auth(principal)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true,\"note\":\"ok\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(requests.findById(stillPending.getId()).orElseThrow().getStatus())
                .isEqualTo(RefundRequestStatus.PENDING);
        assertThat(emails.sent().size()).isEqualTo(before);

        tick();

        RefundRequest after = requests.findById(closed.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundRequestStatus.APPROVED);
        assertThat(after.getRefundId()).isEqualTo(onlyRefund(refunded).getId());
        assertThat(after.getDecisionNote()).isEqualTo("event_cancelled");
        assertThat(after.getDecidedByUserId()).isNull();
        assertThat(after.getPendingMarker()).isNull();

        // No cancellation refund comes while the bank decides: the request is closed, not left to hang.
        RefundRequest waiting = requests.findById(stillPending.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(RefundRequestStatus.APPROVED);
        assertThat(waiting.getDecisionNote()).isEqualTo("event_cancelled:waiting_dispute");
        assertThat(waiting.getRefundId()).isNull();
        assertThat(waiting.getPendingMarker()).isNull();
    }

    @Test
    void tick_noticeReachesTheBuyerBeforeAnInstantRefund() throws Exception {
        Order o = paidOrder(1149, 149, null);
        ticket(o, Ticket.STATE_ISSUED);
        String address = jdbc.queryForObject("SELECT email FROM orders WHERE id = ?", String.class, o.getId());
        AtomicReference<Boolean> noticedFirst = new AtomicReference<>();
        // Card refunds often come back succeeded at once, which fires the refund-confirmed email right away.
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            noticedFirst.set(mailsTo(o).size() == 1);
            return stripeRefund("succeeded");
        });

        cancel();
        tick();
        AsyncDrain.drain(ticketEmailExecutor);

        assertThat(noticedFirst.get()).as("notice sent before Stripe was asked to refund").isTrue();
        List<RecordingEmailService.SentEmail> toBuyer = emails.sent().stream().filter(m -> address.equals(m.to())).toList();
        assertThat(toBuyer).extracting(RecordingEmailService.SentEmail::subject)
                .containsExactly("Cancelled: " + event.getName() + " · order #" + o.getId().toString().substring(0, 8),
                        "Refund confirmed for " + event.getName());
        // True in this branch too: the other email is the confirmation that followed it.
        assertThat(toBuyer.get(0).text()).contains("You will get another email once it is done.");
        assertThat(item(o)).isEqualTo("refunded");
    }

    @Test
    void tick_failedNotice_holdsTheRefundUntilTheNoticeIsSent() throws Exception {
        Order o = paidOrder(1149, 149, null);
        ticket(o, Ticket.STATE_ISSUED);
        AtomicReference<Boolean> noticedFirst = new AtomicReference<>();
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            noticedFirst.set(mailsTo(o).size() == 1);
            return stripeRefund("succeeded");
        });

        cancel();
        emails.failNextSendWith(new IllegalStateException("Resend is down"));
        tick();

        // The notice failed once and will be retried: no refund may overtake it.
        verify(stripeRefunds, never()).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(mailsTo(o)).isEmpty();
        assertThat(item(o)).isEqualTo("pending");

        tick();

        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(noticedFirst.get()).isTrue();
        assertThat(item(o)).isEqualTo("refunded");
    }

    /** Only imin's own cancel takes the decision away; an event set CANCELLED by hand keeps the organizer's. */
    @Test
    void rejectOnAHandCancelledEvent_isStillTheOrganizers() throws Exception {
        Order o = paidOrder(1149, 149, null);
        ticket(o, Ticket.STATE_ISSUED);
        RefundRequest rr = pendingRequest(o);
        jdbc.update("UPDATE events SET status = 'CANCELLED' WHERE id = ?", event.getId());

        mvc.perform(post("/api/v1/orgs/{orgId}/refund-requests/{id}/reject", rr.getOrgId(), rr.getId())
                        .with(auth(principal)).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"no\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"));
        assertThat(requests.findById(rr.getId()).orElseThrow().getStatus()).isEqualTo(RefundRequestStatus.REJECTED);
    }

    enum Notice { FREE, ALREADY, BLANK_CONTACT }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Notice.class)
    void notice_saysWhatIsTrueForTheOrder(Notice notice) throws Exception {
        Order o;
        String paragraph;
        String footer;
        if (notice == Notice.FREE) {
            o = paidOrder(0, 0, "es");
            jdbc.update("UPDATE orders SET stripe_payment_intent_id = NULL, payment_method = 'free' WHERE id = ?", o.getId());
            paragraph = "El pedido " + code(o) + " era gratuito, así que no hay nada que reembolsar.";
            footer = "¿Preguntas? Escribe al organizador (" + contact() + ") o a hello@imin.wtf con tu número de pedido.";
        } else if (notice == Notice.ALREADY) {
            o = paidOrder(1149, 149, "uk");
            ticket(o, Ticket.STATE_REFUNDED);
            priorRefund(o, 1149, 149, RefundStatus.SUCCEEDED);
            paragraph = "Повну суму за замовлення " + code(o)
                    + " вже повернуто або повертається, тож більше нічого не належить.";
            footer = "Є питання? Напишіть організатору (" + contact() + ") або на hello@imin.wtf і вкажіть номер замовлення.";
        } else {
            jdbc.update("UPDATE organizations SET contact_email = '' WHERE id = ?", event.getOrgId());
            o = paidOrder(1149, 149, "fr");
            ticket(o, Ticket.STATE_ISSUED);
            paragraph = "Vous serez remboursé de 11.49 EUR pour la commande " + code(o)
                    + "\u00A0: la totalité de votre paiement, frais de réservation compris.";
            footer = "Des questions\u00A0? Écrivez-nous à hello@imin.wtf, avec votre numéro de commande.";
        }
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> stripeRefund("pending"));

        cancel();
        tick();

        String text = mailTo(o).text();
        assertThat(text).contains(paragraph).contains(footer);
        assertThat(text).doesNotContain("organizer at").doesNotContain("organizador ()").doesNotContain("organisateur ()");
    }

    @Test
    void tick_skipsOtherModeOrder() throws Exception {
        Order live = paidOrder(1149, 149, null);
        jdbc.update("UPDATE orders SET test_mode = false WHERE id = ?", live.getId());
        ticket(live, Ticket.STATE_ISSUED);
        Order own = paidOrder(1149, 149, null);
        ticket(own, Ticket.STATE_ISSUED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> stripeRefund("pending"));

        cancel();
        tick();

        // The tick ran: the running-mode order was refunded and told.
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        assertThat(mailsTo(own)).hasSize(1);
        assertThat(item(live)).isEqualTo("other_mode");
        assertThat(refundsOf(live)).isEmpty();
        assertThat(mailsTo(live)).isEmpty();
        cancellation().andExpect(jsonPath("$.orders.total").value(1));
    }

    // ---------- helpers ----------

    private String code(Order o) {
        return "#" + o.getId().toString().substring(0, 8);
    }

    private String contact() {
        return jdbc.queryForObject("SELECT contact_email FROM organizations WHERE id = ?", String.class, event.getOrgId());
    }

    private void cancel() throws Exception {
        mvc.perform(post("/api/v1/events/{id}/cancel", event.getId())
                        .with(auth(principal)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions cancellation() throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/events/{id}/cancellation", event.getId()).with(auth(principal)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    private void tick() {
        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = 'event_cancellation_refunds'");
        sweeper.tick();
    }

    private Order paidOrder(long totalMinor, long feeMinor, String locale) {
        Order o = fx.order(event, fx.email("buyer"));
        String pi = "pi_" + UUID.randomUUID().toString().replace("-", "");
        // test_mode as checkout stamps it under the suite's sk_test key.
        jdbc.update("UPDATE orders SET stripe_payment_intent_id = ?, application_fee_minor = ?, total_minor = ?, "
                + "test_mode = true, buyer_locale = ? WHERE id = ?", pi, feeMinor, totalMinor, locale, o.getId());
        o.setStripePaymentIntentId(pi);
        o.setTotalMinor(totalMinor);
        return o;
    }

    private Ticket ticket(Order order, String state) {
        Ticket t = fx.ticket(order, state);
        jdbc.update("UPDATE tickets SET tier_id = ? WHERE id = ?", tier.getId(), t.getId());
        return t;
    }

    private UUID priorRefund(Order o, long amount, long fee, RefundStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO refunds (id, order_id, stripe_payment_intent_id, amount_minor, currency,
                    application_fee_refund_minor, reason, status, initiated_by_user_id, idempotency_key,
                    stripe_attempt_at, stripe_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'EUR', ?, 'OTHER', ?, ?, ?, ?, 1, ?, ?)
                """, id, o.getId(), o.getStripePaymentIntentId(), amount, fee, status.name(), principal.userId(),
                "prior-" + id, java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant()));
        return id;
    }

    private Dispute dispute(Order o, long amount, DisputeStatus status) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().substring(0, 12));
        d.setOrgId(o.getOrgId());
        d.setEventId(o.getEventId());
        d.setOrderId(o.getId());
        d.setAmountMinor(amount);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setTestMode(true);
        d.setOpenedAt(Instant.parse("2026-09-01T10:15:30Z"));
        return disputes.save(d);
    }

    private RefundRequest pendingRequest(Order o) {
        RefundRequest rr = new RefundRequest();
        rr.setReference(references.next());
        rr.setOrderId(o.getId());
        rr.setOrgId(o.getOrgId());
        rr.setEventId(o.getEventId());
        rr.setBuyerEmail(o.getEmail());
        rr.setReason(RefundRequestReason.CANT_ATTEND);
        rr.setExplanation("can't make it");
        rr.setStatus(RefundRequestStatus.PENDING);
        rr.setPendingMarker(o.getId());
        return requests.save(rr);
    }

    private static com.stripe.model.Refund stripeRefund(String status) {
        com.stripe.model.Refund r = new com.stripe.model.Refund();
        r.setId("re_" + UUID.randomUUID().toString().replace("-", ""));
        r.setCharge("ch_" + UUID.randomUUID().toString().replace("-", ""));
        r.setStatus(status);
        return r;
    }

    private List<Refund> refundsOf(Order o) {
        return refunds.findByOrderIdOrderByCreatedAtDesc(o.getId());
    }

    private Refund onlyRefund(Order o) {
        List<Refund> rows = refundsOf(o);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<UUID> claims(Refund r) {
        return jdbc.queryForList("SELECT ticket_id FROM refund_tickets WHERE refund_id = ?", UUID.class, r.getId());
    }

    private String item(Order o) {
        return jdbc.queryForObject("SELECT state FROM event_cancellation_orders WHERE order_id = ?",
                String.class, o.getId());
    }

    private int attempts(Order o) {
        return jdbc.queryForObject("SELECT attempts FROM event_cancellation_orders WHERE order_id = ?",
                Integer.class, o.getId());
    }

    private List<RecordingEmailService.SentEmail> mailsTo(Order o) {
        String address = jdbc.queryForObject("SELECT email FROM orders WHERE id = ?", String.class, o.getId());
        return emails.sent().stream()
                .filter(m -> address.equals(m.to()) && m.subject().contains(event.getName())
                        && !m.subject().contains("Refund") && !m.subject().contains("Remboursement"))
                .toList();
    }

    private RecordingEmailService.SentEmail mailTo(Order o) {
        List<RecordingEmailService.SentEmail> mails = mailsTo(o);
        assertThat(mails).hasSize(1);
        return mails.get(0);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
