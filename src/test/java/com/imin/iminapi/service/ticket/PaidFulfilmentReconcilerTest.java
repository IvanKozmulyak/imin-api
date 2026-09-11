package com.imin.iminapi.service.ticket;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentIntentCollection;
import com.stripe.model.checkout.SessionCollection;
import com.stripe.service.ChargeService;
import com.stripe.service.CheckoutService;
import com.stripe.service.PaymentIntentService;
import com.stripe.service.checkout.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reconciler is the ONLY back-fill path for a permanently lost
 * {@code payment_intent.succeeded} webhook — the buyer it exists for is one who
 * has been charged and has nothing. It runs on a bare {@code @Scheduled} method,
 * so unless {@code issuePaidOrder} opens a transaction of its own there is no
 * ambient one: every {@code save} commits separately (a crash mid-loop leaves a
 * ticket-less Order that every later tick skips at the "already fulfilled" check),
 * and — worse — {@code publishEvent} runs with no synchronization active, so
 * Spring silently drops it for all three {@code AFTER_COMMIT} listeners
 * ({@code fallbackExecution=false}). The rescued buyer would get DB rows and no
 * ticket email, no audience row and no reforecast.
 */
@SpringBootTest
@Import({TestRateLimitConfig.class, PaidFulfilmentReconcilerTest.IssuedEventRecorder.class})
class PaidFulfilmentReconcilerTest {

    /**
     * Stands in for the three production listeners, which are all
     * {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async} — the async
     * hop makes them useless as an assertion, the AFTER_COMMIT phase is the whole
     * defect. Same phase, same default {@code fallbackExecution=false}, synchronous.
     */
    @TestConfiguration
    static class IssuedEventRecorder {
        final List<UUID> afterCommit = new ArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onTicketsIssued(TicketsIssuedEvent evt) { afterCommit.add(evt.orderId()); }
    }

    @Autowired PaidFulfilmentReconciler reconciler;
    @Autowired IssuedEventRecorder recorder;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean StripeClient stripeClient;

    private PaymentIntentService paymentIntentService;
    private SessionService sessionService;
    private ChargeService chargeService;

    private Event event;
    private TicketTier tier;

    @BeforeEach
    void setUp() throws Exception {
        paymentIntentService = mock(PaymentIntentService.class);
        CheckoutService checkoutService = mock(CheckoutService.class);
        sessionService = mock(SessionService.class);
        chargeService = mock(ChargeService.class);
        when(stripeClient.paymentIntents()).thenReturn(paymentIntentService);
        when(stripeClient.checkout()).thenReturn(checkoutService);
        when(checkoutService.sessions()).thenReturn(sessionService);
        when(stripeClient.charges()).thenReturn(chargeService);

        // lockAtLeastFor=PT1M would make every test after the first skip its tick. Expired,
        // not deleted: ShedLock remembers the row exists and only ever UPDATEs it.
        jdbc.update("UPDATE shedlock SET lock_until = ?, locked_at = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(600)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(900)));

        recorder.afterCommit.clear();
        tickets.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();

        Organization org = new Organization();
        org.setName("Reconcile Org");
        org.setSlug("reconcile-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("reconcile@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("reconcile-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Lost Webhook Night");
        event.setSlug("reconcile-event-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setPublishedAt(Instant.now().minusSeconds(3600));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        tier = new TicketTier();
        tier.setEventId(event.getId());
        tier.setName("GA");
        tier.setPriceMinor(1500);
        tier.setQuantity(100);
        tier.setReserved(0);
        tier.setSold(0);
        tier.setEnabled(true);
        tier = tiers.save(tier);
    }

    @AfterEach
    void tearDown() {
        recorder.afterCommit.clear();
        tickets.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    void back_filled_order_gets_its_tickets_and_fires_the_after_commit_listeners() throws Exception {
        PaymentIntent pi = succeededTicketPi("pi_reconcile_1", 3000, 2);
        wireList(pi);

        reconciler.reconcile();

        Order order = orders.findByStripePaymentIntentId("pi_reconcile_1").orElseThrow();
        List<Ticket> issued = tickets.findByOrderIdOrderByCreatedAtAsc(order.getId());
        assertThat(issued).hasSize(2);
        assertThat(recorder.afterCommit)
                .as("the rescued buyer's ticket email, audience row and reforecast all hang "
                        + "off an AFTER_COMMIT listener — with no transaction Spring drops the event")
                .containsExactly(order.getId());
    }

    /**
     * The reconciler issues tickets, so it carries the same amount gate the webhook does —
     * otherwise one permanently-lost webhook is all it takes to fulfil a PI charging an
     * amount imin never priced.
     */
    @Test
    void amount_mismatch_is_refused_instead_of_back_filled() throws Exception {
        PaymentIntent pi = succeededTicketPi("pi_reconcile_mismatch", 999, 2);
        stampExpectedTotal(pi, 3348, "eur");   // priced 33.48, charged 9.99
        wireList(pi);

        reconciler.reconcile();

        verify(paymentIntentService).list(any(com.stripe.param.PaymentIntentListParams.class));
        assertThat(orders.findByStripePaymentIntentId("pi_reconcile_mismatch"))
                .as("a PI charging an amount we never priced must issue nothing")
                .isEmpty();
        assertThat(recorder.afterCommit).isEmpty();
    }

    @Test
    void matching_stamped_amount_is_back_filled_as_before() throws Exception {
        PaymentIntent pi = succeededTicketPi("pi_reconcile_match", 3348, 2);
        stampExpectedTotal(pi, 3348, "eur");
        wireList(pi);

        reconciler.reconcile();

        Order order = orders.findByStripePaymentIntentId("pi_reconcile_match").orElseThrow();
        assertThat(tickets.findByOrderIdOrderByCreatedAtAsc(order.getId())).hasSize(2);
        assertThat(recorder.afterCommit).containsExactly(order.getId());
    }

    // ─── Stripe fixture helpers ──────────────────────────────────────────────

    /** Adds the checkout-time price stamp the verifier compares the charge against. */
    private void stampExpectedTotal(PaymentIntent pi, long expectedMinor, String currency) {
        Map<String, String> meta = new java.util.HashMap<>(pi.getMetadata());
        meta.put("expected_total_minor", String.valueOf(expectedMinor));
        meta.put("expected_currency", currency);
        pi.setMetadata(meta);
    }

    private PaymentIntent succeededTicketPi(String id, long amount, int qty) throws Exception {
        PaymentIntent p = new PaymentIntent();
        p.setId(id);
        p.setStatus("succeeded");
        p.setAmount(amount);
        p.setCurrency("eur");
        p.setLatestCharge("ch_for_" + id);
        p.setMetadata(Map.of(
                "reservation_id", UUID.randomUUID().toString(),
                "tier_id", tier.getId().toString(),
                "qty", String.valueOf(qty),
                "event_id", event.getId().toString(),
                "buyer_email", "rescued-buyer@example.test",
                "client", "web"));

        Charge c = new Charge();
        Charge.BillingDetails bd = new Charge.BillingDetails();
        bd.setEmail("rescued-buyer@example.test");
        c.setBillingDetails(bd);
        when(chargeService.retrieve(eq(p.getLatestCharge()))).thenReturn(c);

        SessionCollection empty = new SessionCollection();
        empty.setData(List.of());
        when(sessionService.list(any(com.stripe.param.checkout.SessionListParams.class)))
                .thenReturn(empty);
        return p;
    }

    /**
     * One page of PaymentIntents. Note the reconciler is {@code @SchedulerLock}ed with
     * {@code lockAtLeastFor = PT1M}, so a second {@code reconcile()} inside the same
     * minute is skipped by ShedLock — this class ticks exactly once.
     */
    private void wireList(PaymentIntent... pis) throws Exception {
        PaymentIntentCollection coll = mock(PaymentIntentCollection.class);
        when(coll.autoPagingIterable()).thenReturn(List.of(pis));
        when(paymentIntentService.list(any(com.stripe.param.PaymentIntentListParams.class)))
                .thenReturn(coll);
    }
}
