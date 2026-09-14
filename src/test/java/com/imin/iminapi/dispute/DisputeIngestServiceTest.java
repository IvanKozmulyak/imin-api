package com.imin.iminapi.dispute;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.stripe.SettlementIngestService;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.model.Charge;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two pieces of dispute ingestion that are pure logic rather than persistence: the Stripe
 * status mapping, and the rule that {@link DisputeOpenedEvent} fires on the TRANSITION into
 * OPEN rather than on every delivery — which is the only thing standing between one organizer
 * alert and one per Stripe redelivery.
 *
 * <p>The ticket/registry side is covered end-to-end through the webhook in
 * {@code SettlementIngestServiceTest}.
 */
class DisputeIngestServiceTest {

    private DisputeRepository disputes;
    private OrderRepository orders;
    private TicketRepository tickets;
    private OrganizationRepository orgs;
    private SettlementIngestService settlementIngest;
    private ApplicationEventPublisher publisher;
    private StripeProperties stripeProps;
    private DisputeIngestService svc;

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        disputes = mock(DisputeRepository.class);
        orders = mock(OrderRepository.class);
        tickets = mock(TicketRepository.class);
        orgs = mock(OrganizationRepository.class);
        settlementIngest = mock(SettlementIngestService.class);
        publisher = mock(ApplicationEventPublisher.class);
        stripeProps = new StripeProperties();
        stripeProps.setSecretKey("sk_test_dummy");
        svc = new DisputeIngestService(disputes, orders, tickets, orgs, settlementIngest, publisher,
                stripeProps);

        Charge charge = ApiResource.GSON.fromJson("""
                { "id": "ch_1", "object": "charge", "payment_intent": "pi_1",
                  "transfer_data": { "destination": "acct_1" } }
                """, Charge.class);
        when(settlementIngest.resolveDisputedCharge(any(), any(), anyString())).thenReturn(charge);

        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrgId(ORG_ID);
        order.setEventId(EVENT_ID);
        when(orders.findByStripePaymentIntentId("pi_1")).thenReturn(Optional.of(order));
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of());
        when(disputes.findByStripePaymentIntentIdAndOrderIdIsNull("pi_1"))
                .thenReturn(List.of(orphan(DisputeStatus.OPEN)));
        when(disputes.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"needs_response", "under_review", "warning_needs_response",
            "warning_under_review"})
    void fromStripeMapsInProgressStatusesToOpen(String stripeStatus) {
        assertThat(DisputeStatus.fromStripe(stripeStatus))
                .as("anything Stripe has not resolved still has funds at risk")
                .isEqualTo(DisputeStatus.OPEN);
    }

    /** An inquiry that closed never took the money; leaving it OPEN froze payouts forever. */
    @Test
    void warningClosedIsNotAnOpenDispute() {
        assertThat(DisputeStatus.fromStripe("warning_closed"))
                .isEqualTo(DisputeStatus.WITHDRAWN_REINSTATED);
    }

    @Test
    void closedWithAWarningStatusRestoresTicketsAndUnblocksPayouts() {
        Ticket revoked = new Ticket();
        revoked.setOrderId(ORDER_ID);
        revoked.setState(Ticket.STATE_REVOKED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(revoked));
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.of(openRow()));

        svc.ingest(stripeDispute("warning_closed"), "acct_1", "charge.dispute.closed",
                Instant.parse("2026-09-11T10:00:00Z"));

        ArgumentCaptor<Dispute> saved = ArgumentCaptor.forClass(Dispute.class);
        verify(disputes).save(saved.capture());
        assertThat(saved.getValue().getStatus())
                .as("a closed inquiry must stop counting as an open dispute")
                .isEqualTo(DisputeStatus.WITHDRAWN_REINSTATED);
        assertThat(saved.getValue().getClosedAt()).isNotNull();
        assertThat(revoked.getState()).isEqualTo(Ticket.STATE_ISSUED);
        verify(tickets).saveAll(anyList());
    }

    @Test
    void closedLostKeepsTheTicketsRevoked() {
        Ticket revoked = new Ticket();
        revoked.setOrderId(ORDER_ID);
        revoked.setState(Ticket.STATE_REVOKED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(revoked));
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.of(openRow()));

        svc.ingest(stripeDispute("lost"), "acct_1", "charge.dispute.closed",
                Instant.parse("2026-09-11T10:00:00Z"));

        ArgumentCaptor<Dispute> saved = ArgumentCaptor.forClass(Dispute.class);
        verify(disputes).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DisputeStatus.LOST);
        assertThat(revoked.getState()).isEqualTo(Ticket.STATE_REVOKED);
        verify(tickets, never()).saveAll(anyList());
    }

    @Test
    void closedWonRestoresTheTickets() {
        Ticket revoked = new Ticket();
        revoked.setOrderId(ORDER_ID);
        revoked.setState(Ticket.STATE_REVOKED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(revoked));
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.of(openRow()));

        svc.ingest(stripeDispute("won"), "acct_1", "charge.dispute.closed",
                Instant.parse("2026-09-11T10:00:00Z"));

        ArgumentCaptor<Dispute> saved = ArgumentCaptor.forClass(Dispute.class);
        verify(disputes).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(revoked.getState()).isEqualTo(Ticket.STATE_ISSUED);
    }

    /**
     * A ticket scanned before the chargeback must come back as {@code redeemed}: restoring it to
     * {@code issued} would let the same QR through the door a second time.
     */
    @Test
    void anAlreadyRedeemedTicketIsRestoredToRedeemed() {
        Instant scannedAt = Instant.parse("2026-09-01T21:30:00Z");
        Ticket wasRedeemed = new Ticket();
        wasRedeemed.setOrderId(ORDER_ID);
        wasRedeemed.setState(Ticket.STATE_REVOKED);
        wasRedeemed.setRedeemedAt(scannedAt);
        Ticket neverScanned = new Ticket();
        neverScanned.setOrderId(ORDER_ID);
        neverScanned.setState(Ticket.STATE_REVOKED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(wasRedeemed, neverScanned));
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.of(openRow()));

        svc.ingest(stripeDispute("won"), "acct_1", "charge.dispute.closed",
                Instant.parse("2026-09-11T10:00:00Z"));

        assertThat(wasRedeemed.getState()).isEqualTo(Ticket.STATE_REDEEMED);
        assertThat(wasRedeemed.getRedeemedAt()).isEqualTo(scannedAt);
        assertThat(neverScanned.getState()).isEqualTo(Ticket.STATE_ISSUED);
    }

    /**
     * Two chargebacks against the same order: the first to close must not hand the buyer working
     * tickets back while the second is still live. Revocation is per order, so the last open
     * dispute is the only one that may restore.
     */
    @Test
    void aWinLeavesTheTicketsRevokedWhileAnotherDisputeOnTheOrderIsStillOpen() {
        Ticket revoked = new Ticket();
        revoked.setOrderId(ORDER_ID);
        revoked.setState(Ticket.STATE_REVOKED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(revoked));
        Dispute row = openRow();
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.of(row));
        when(disputes.countOtherOpenByOrderId(ORDER_ID, row.getId())).thenReturn(1L);

        svc.ingest(stripeDispute("won"), "acct_1", "charge.dispute.closed",
                Instant.parse("2026-09-11T10:00:00Z"));

        assertThat(revoked.getState()).isEqualTo(Ticket.STATE_REVOKED);
        verify(tickets, never()).saveAll(anyList());
    }

    @Test
    void aWinRestoresTheTicketsOnceItIsTheLastOpenDisputeOnTheOrder() {
        Ticket revoked = new Ticket();
        revoked.setOrderId(ORDER_ID);
        revoked.setState(Ticket.STATE_REVOKED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(revoked));
        Dispute row = openRow();
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.of(row));
        when(disputes.countOtherOpenByOrderId(ORDER_ID, row.getId())).thenReturn(0L);

        svc.ingest(stripeDispute("won"), "acct_1", "charge.dispute.closed",
                Instant.parse("2026-09-11T10:00:00Z"));

        assertThat(revoked.getState()).isEqualTo(Ticket.STATE_ISSUED);
        verify(tickets).saveAll(anyList());
    }

    @Test
    void aFirstSightingUnderALiveKeyIsStampedLive() {
        stripeProps.setSecretKey("sk_live_dummy");

        svc.ingest(stripeDispute("needs_response"), "acct_1", "charge.dispute.created",
                Instant.parse("2026-09-11T10:00:00Z"));

        assertThat(savedRow().isTestMode())
                .as("a live chargeback must withhold its face value from the payout net")
                .isFalse();
    }

    @Test
    void aFirstSightingUnderATestKeyIsStampedTestMode() {
        stripeProps.setSecretKey("sk_test_dummy");

        svc.ingest(stripeDispute("needs_response"), "acct_1", "charge.dispute.created",
                Instant.parse("2026-09-11T10:00:00Z"));

        assertThat(savedRow().isTestMode())
                .as("test-era money clawed nothing back, so it withholds nothing")
                .isTrue();
    }

    @Test
    void aLaterDeliveryDoesNotReStampAnExistingRow() {
        Dispute existing = openRow();
        existing.setTestMode(true);
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.of(existing));
        stripeProps.setSecretKey("sk_live_dummy");

        svc.ingest(stripeDispute("lost"), "acct_1", "charge.dispute.closed",
                Instant.parse("2026-09-11T10:00:00Z"));

        assertThat(savedRow().isTestMode())
                .as("the era is the first sighting's, not that of whichever key is running later")
                .isTrue();
    }

    // ── late attachment (the dispute-before-order race) ───────────────────────────

    /** The race's whole point: an OPEN dispute revoked nothing, so attaching must revoke now. */
    @Test
    void attachingAnOpenOrphanRevokesTheOrdersTickets() {
        Ticket live = new Ticket();
        live.setOrderId(ORDER_ID);
        live.setState(Ticket.STATE_ISSUED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(live));
        when(disputes.attachToOrder(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        int attached = svc.attachOrphansForOrder(order(false));

        assertThat(attached).isEqualTo(1);
        assertThat(live.getState()).isEqualTo(Ticket.STATE_REVOKED);
        verify(tickets).saveAll(anyList());
    }

    /** A dispute already LOST when we matched it was never revoked at OPEN, and the money is gone. */
    @Test
    void attachingALostOrphanAlsoRevokes() {
        Ticket live = new Ticket();
        live.setOrderId(ORDER_ID);
        live.setState(Ticket.STATE_ISSUED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(live));
        when(disputes.findByStripePaymentIntentIdAndOrderIdIsNull("pi_1"))
                .thenReturn(List.of(orphan(DisputeStatus.LOST)));
        when(disputes.attachToOrder(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        svc.attachOrphansForOrder(order(false));

        assertThat(live.getState()).isEqualTo(Ticket.STATE_REVOKED);
    }

    /** A dispute the organizer already won took nothing; attaching it must not kill the tickets. */
    @Test
    void attachingAWonOrphanRevokesNothing() {
        Ticket live = new Ticket();
        live.setOrderId(ORDER_ID);
        live.setState(Ticket.STATE_ISSUED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(live));
        when(disputes.findByStripePaymentIntentIdAndOrderIdIsNull("pi_1"))
                .thenReturn(List.of(orphan(DisputeStatus.WON)));
        when(disputes.attachToOrder(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        svc.attachOrphansForOrder(order(false));

        assertThat(live.getState()).isEqualTo(Ticket.STATE_ISSUED);
        verify(tickets, never()).saveAll(anyList());
    }

    /**
     * {@code test_mode} is the ORDER's answer, not the running key's: a live-key sweep attaching
     * a test-era orphan under {@code isLiveKey()} would withhold real face value from the net.
     */
    @Test
    void lateAttachmentTakesTestModeFromTheOrderNotTheRunningKey() {
        stripeProps.setSecretKey("sk_live_dummy");
        when(disputes.attachToOrder(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        svc.attachOrphansForOrder(order(true));

        verify(disputes).attachToOrder(eq(ORPHAN_ID), eq(ORDER_ID), eq(EVENT_ID), eq(ORG_ID), eq(true),
                any());
    }

    /** The conditional UPDATE lost the race: another path already attached it, so do nothing. */
    @Test
    void aLostConditionalUpdateRevokesNothing() {
        Ticket live = new Ticket();
        live.setOrderId(ORDER_ID);
        live.setState(Ticket.STATE_ISSUED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(live));
        when(disputes.attachToOrder(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(0);

        int attached = svc.attachOrphansForOrder(order(false));

        assertThat(attached).isZero();
        assertThat(live.getState()).isEqualTo(Ticket.STATE_ISSUED);
        verify(tickets, never()).saveAll(anyList());
    }

    /** An order that never reached Stripe has no PI to match on — and must not query for one. */
    @Test
    void anOrderWithNoPaymentIntentIsNotEvenLookedUp() {
        Order noPi = order(false);
        noPi.setStripePaymentIntentId(null);

        assertThat(svc.attachOrphansForOrder(noPi)).isZero();
        verify(disputes, never()).findByStripePaymentIntentIdAndOrderIdIsNull(anyString());
    }

    /**
     * {@code ingest} already alerted the organizer on the transition into OPEN. Publishing again
     * here would mean two chargeback emails for one chargeback.
     */
    @Test
    void lateAttachmentPublishesNoSecondDisputeOpenedEvent() {
        when(disputes.attachToOrder(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        svc.attachOrphansForOrder(order(false));

        verify(publisher, never()).publishEvent(any(DisputeOpenedEvent.class));
    }

    /** The sweeper's entry point: it has already matched the row to an order itself. */
    @Test
    void attachOrphanRevokesForAnAlreadyMatchedRow() {
        Ticket live = new Ticket();
        live.setOrderId(ORDER_ID);
        live.setState(Ticket.STATE_ISSUED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(live));
        when(disputes.attachToOrder(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        assertThat(svc.attachOrphan(orphan(DisputeStatus.OPEN), order(false))).isTrue();
        assertThat(live.getState()).isEqualTo(Ticket.STATE_REVOKED);
    }

    /** A withdrawn/reinstated dispute gave the money back; attaching it must not kill the tickets. */
    @Test
    void attachingAWithdrawnReinstatedOrphanRevokesNothing() {
        Ticket live = new Ticket();
        live.setOrderId(ORDER_ID);
        live.setState(Ticket.STATE_ISSUED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(live));
        when(disputes.findByStripePaymentIntentIdAndOrderIdIsNull("pi_1"))
                .thenReturn(List.of(orphan(DisputeStatus.WITHDRAWN_REINSTATED)));
        when(disputes.attachToOrder(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        svc.attachOrphansForOrder(order(false));

        assertThat(live.getState()).isEqualTo(Ticket.STATE_ISSUED);
        verify(tickets, never()).saveAll(anyList());
    }

    /**
     * An orphan's org was guessed from the charge's transfer destination, which can disagree with
     * the order's; the order is the precise answer and the payout freeze is counted per org.
     */
    @Test
    void lateAttachmentTakesTheOrgFromTheOrderNotTheGuessedOne() {
        Dispute guessedWrong = orphan(DisputeStatus.OPEN);
        guessedWrong.setOrgId(UUID.randomUUID());
        when(disputes.findByStripePaymentIntentIdAndOrderIdIsNull("pi_1"))
                .thenReturn(List.of(guessedWrong));
        when(disputes.attachToOrder(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        svc.attachOrphansForOrder(order(false));

        verify(disputes).attachToOrder(eq(ORPHAN_ID), eq(ORDER_ID), eq(EVENT_ID), eq(ORG_ID),
                eq(false), any());
    }

    // ── the same race seen by a later webhook delivery ────────────────────────────

    /**
     * The delivery that finally resolves the charge back-fills {@code order_id} — after which
     * both attach paths (they filter {@code order_id is null}) can never see the row again. So
     * this delivery is the last chance to revoke, and it has to take it.
     */
    @Test
    void aLaterDeliveryThatFinallyFindsTheOrderRevokesItsTickets() {
        Ticket live = new Ticket();
        live.setOrderId(ORDER_ID);
        live.setState(Ticket.STATE_ISSUED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(live));
        when(orders.findByStripePaymentIntentId("pi_1")).thenReturn(Optional.of(order(false)));
        Dispute guessedWrongOrg = orphanRow(DisputeStatus.OPEN);
        guessedWrongOrg.setOrgId(UUID.randomUUID());
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.of(guessedWrongOrg));

        svc.ingest(stripeDispute("needs_response"), "acct_1", "charge.dispute.funds_withdrawn",
                Instant.parse("2026-09-11T10:00:00Z"));

        assertThat(live.getState()).isEqualTo(Ticket.STATE_REVOKED);
        verify(tickets).saveAll(anyList());
        Dispute saved = savedRow();
        assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(saved.getEventId()).isEqualTo(EVENT_ID);
        assertThat(saved.getOrgId())
                .as("the order's org replaces the one guessed from the charge's destination")
                .isEqualTo(ORG_ID);
        assertThat(saved.isTestMode())
                .as("the order, not the first sighting's key, records whether the money was real")
                .isFalse();
        verify(publisher, never())
                .publishEvent(any(DisputeOpenedEvent.class));
    }

    /** Same path, dispute already lost by the time the order turned up: the money is gone. */
    @Test
    void aLaterDeliveryThatFindsTheOrderOnALostDisputeAlsoRevokes() {
        Ticket live = new Ticket();
        live.setOrderId(ORDER_ID);
        live.setState(Ticket.STATE_ISSUED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(live));
        when(orders.findByStripePaymentIntentId("pi_1")).thenReturn(Optional.of(order(false)));
        when(disputes.findByStripeDisputeId("du_1"))
                .thenReturn(Optional.of(orphanRow(DisputeStatus.OPEN)));

        svc.ingest(stripeDispute("lost"), "acct_1", "charge.dispute.closed",
                Instant.parse("2026-09-11T10:00:00Z"));

        assertThat(live.getState()).isEqualTo(Ticket.STATE_REVOKED);
        verify(publisher, never())
                .publishEvent(any(DisputeOpenedEvent.class));
    }

    /** A dispute the organizer won by the time we matched it took nothing — revoke nothing. */
    @Test
    void aLaterDeliveryThatFindsTheOrderOnAWonDisputeRevokesNothing() {
        Ticket live = new Ticket();
        live.setOrderId(ORDER_ID);
        live.setState(Ticket.STATE_ISSUED);
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(live));
        when(orders.findByStripePaymentIntentId("pi_1")).thenReturn(Optional.of(order(false)));
        when(disputes.findByStripeDisputeId("du_1"))
                .thenReturn(Optional.of(orphanRow(DisputeStatus.OPEN)));

        svc.ingest(stripeDispute("won"), "acct_1", "charge.dispute.closed",
                Instant.parse("2026-09-11T10:00:00Z"));

        assertThat(live.getState()).isEqualTo(Ticket.STATE_ISSUED);
        verify(tickets, never()).saveAll(anyList());
    }

    /** The race's leftovers as a later delivery finds them: a row that never had an order. */
    private static Dispute orphanRow(DisputeStatus status) {
        Dispute existing = new Dispute();
        existing.setId(UUID.randomUUID());
        existing.setStripeDisputeId("du_1");
        existing.setOrgId(ORG_ID);
        existing.setStripePaymentIntentId("pi_1");
        existing.setStatus(status);
        existing.setTestMode(true);
        existing.setLastEventAt(Instant.parse("2026-09-10T10:00:00Z"));
        return existing;
    }

    private static final UUID ORPHAN_ID = UUID.randomUUID();

    private static Dispute orphan(DisputeStatus status) {
        Dispute d = new Dispute();
        d.setId(ORPHAN_ID);
        d.setStripeDisputeId("du_orphan");
        d.setOrgId(ORG_ID);
        d.setStripePaymentIntentId("pi_1");
        d.setAmountMinor(4200);
        d.setCurrency("eur");
        d.setStatus(status);
        return d;
    }

    private static Order order(boolean testMode) {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setOrgId(ORG_ID);
        o.setEventId(EVENT_ID);
        o.setStripePaymentIntentId("pi_1");
        o.setTestMode(testMode);
        return o;
    }

    private Dispute savedRow() {

        ArgumentCaptor<Dispute> saved = ArgumentCaptor.forClass(Dispute.class);
        verify(disputes).save(saved.capture());
        return saved.getValue();
    }

    private Dispute openRow() {
        Dispute existing = new Dispute();
        existing.setId(UUID.randomUUID());
        existing.setStripeDisputeId("du_1");
        existing.setOrgId(ORG_ID);
        existing.setOrderId(ORDER_ID);
        existing.setEventId(EVENT_ID);
        existing.setStatus(DisputeStatus.OPEN);
        existing.setLastEventAt(Instant.parse("2026-09-10T10:00:00Z"));
        return existing;
    }

    @Test
    void closedLostIsTerminal() {
        assertThat(DisputeStatus.fromStripe("lost")).isEqualTo(DisputeStatus.LOST);
        assertThat(DisputeStatus.fromStripe("won")).isEqualTo(DisputeStatus.WON);
        assertThat(DisputeStatus.LOST.toWire()).isEqualTo("lost");
        assertThat(DisputeStatus.WITHDRAWN_REINSTATED.toWire()).isEqualTo("withdrawn_reinstated");
    }

    @Test
    void disputeOpenedIsPublishedOnceAndNotOnReplay() {
        com.stripe.model.Dispute stripeDispute = stripeDispute("needs_response");

        // First delivery: no row yet.
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.empty());
        svc.ingest(stripeDispute, "acct_1", "charge.dispute.created", Instant.parse("2026-09-10T10:00:00Z"));

        // Stripe redelivers the same event; the row is now present and already OPEN.
        Dispute existing = new Dispute();
        existing.setId(UUID.randomUUID());
        existing.setStripeDisputeId("du_1");
        existing.setOrgId(ORG_ID);
        existing.setStatus(DisputeStatus.OPEN);
        existing.setLastEventAt(Instant.parse("2026-09-10T10:00:00Z"));
        when(disputes.findByStripeDisputeId("du_1")).thenReturn(Optional.of(existing));
        svc.ingest(stripeDispute, "acct_1", "charge.dispute.created", Instant.parse("2026-09-10T10:00:00Z"));

        verify(publisher, times(1))
                .publishEvent(any(DisputeOpenedEvent.class));
    }

    private static com.stripe.model.Dispute stripeDispute(String status) {
        return ApiResource.GSON.fromJson("""
                { "id": "du_1", "object": "dispute", "amount": 4200, "currency": "eur",
                  "reason": "fraudulent", "status": "%s", "charge": "ch_1" }
                """.formatted(status), com.stripe.model.Dispute.class);
    }
}
