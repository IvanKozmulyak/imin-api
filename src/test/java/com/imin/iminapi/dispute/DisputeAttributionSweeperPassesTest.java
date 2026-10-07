package com.imin.iminapi.dispute;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hand-off between the two passes. A second revoke of pass 1's row leaves no trace in the database
 * (its tickets are already revoked), so only the call count can show pass 2 skipped it.
 */
class DisputeAttributionSweeperPassesTest {

    private final DisputeRepository disputes = mock(DisputeRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final DisputeIngestService ingest = mock(DisputeIngestService.class);
    private final DisputeAttributionSweeper sweeper = new DisputeAttributionSweeper(disputes, orders, ingest);

    @Test
    void passTwo_skipsTheRowPassOneAttachedThisTick_andRevokesTheOtherOnce() {
        Order orderA = order();
        Order orderB = order();
        Dispute orphanA = dispute(null);
        Dispute attributedB = dispute(orderB);
        when(disputes.findByOrderIdIsNullAndStripePaymentIntentIdIsNotNullAndCreatedAtAfter(any(), any()))
                .thenReturn(List.of(orphanA));
        when(orders.findByStripePaymentIntentId(anyString())).thenReturn(Optional.of(orderA));
        when(ingest.attachOrphan(orphanA, orderA)).thenReturn(true);
        // Pass 2's query sees pass 1's write in the same transaction, so it returns the orphan too.
        Dispute orphanAttached = dispute(orderA);
        orphanAttached.setId(orphanA.getId());
        when(disputes.findAttributedWithLiveTickets(any(), any(), any()))
                .thenReturn(List.of(orphanAttached, attributedB));
        when(orders.findById(orderA.getId())).thenReturn(Optional.of(orderA));
        when(orders.findById(orderB.getId())).thenReturn(Optional.of(orderB));
        when(ingest.revokeAttributed(attributedB, orderB)).thenReturn(1);

        sweeper.sweep();

        verify(ingest, times(1)).revokeAttributed(attributedB, orderB);
        verify(ingest, never()).revokeAttributed(orphanAttached, orderA);
    }

    private static Order order() {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        return o;
    }

    private static Dispute dispute(Order order) {
        Dispute d = new Dispute();
        d.setId(UUID.randomUUID());
        d.setStatus(DisputeStatus.OPEN);
        d.setStripePaymentIntentId(order == null ? "pi_" + UUID.randomUUID() : order.getStripePaymentIntentId());
        if (order != null) d.setOrderId(order.getId());
        return d;
    }
}
