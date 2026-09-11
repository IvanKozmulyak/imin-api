package com.imin.iminapi.dispute;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.stripe.SettlementIngestService;
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
import static org.mockito.ArgumentMatchers.anyString;
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
        svc = new DisputeIngestService(disputes, orders, tickets, orgs, settlementIngest, publisher);

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
