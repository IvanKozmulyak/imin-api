package com.imin.iminapi.service.event;

import com.imin.iminapi.dispute.DisputeOrderRow;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeWithholding;
import com.imin.iminapi.dto.event.EventSalesFigures;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EventSalesTotalsTest {

    TicketTierRepository tiers = mock(TicketTierRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    RefundRepository refunds = mock(RefundRepository.class);
    DisputeRepository disputes = mock(DisputeRepository.class);
    TicketRepository tickets = mock(TicketRepository.class);
    EventSalesTotals sut = new EventSalesTotals(tiers, orders, refunds,
            new DisputeWithholding(disputes, tickets, refunds));

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    @Test
    void empty_ids_query_nothing() {
        assertThat(sut.forEvents(List.of())).isEmpty();

        verifyNoInteractions(tiers, orders, refunds, disputes, tickets);
    }

    @Test
    void a_page_is_one_grouped_query_per_figure_never_one_per_event() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        List<UUID> ids = List.of(a, b, c);
        when(tiers.sumSoldAndQuantityByEventIds(ids)).thenReturn(rows(
                new Object[] {a, 5L, 200L}, new Object[] {b, 2L, 80L}));
        when(orders.sumTotalMinorByEventIds(ids)).thenReturn(rows(
                new Object[] {a, 5000L}, new Object[] {b, 2000L}));
        when(refunds.sumSucceededRefundMinorByEventIds(ids)).thenReturn(rows(new Object[] {a, 1000L}));
        when(tickets.countRevokedInDisputedOrdersByEventIds(ids, DisputeWithholding.STATUSES))
                .thenReturn(rows(new Object[] {b, 1L}));
        UUID orderB = UUID.randomUUID();
        when(disputes.withholdingRowsByEventIds(ids, DisputeWithholding.STATUSES))
                .thenReturn(List.of(new DisputeOrderRow(b, orderB, 1000L, 0L, 1000L)));
        when(refunds.sumSucceededAmountAndFeeByOrderIds(Set.of(orderB))).thenReturn(List.of());

        Map<UUID, EventSalesFigures> out = sut.forEvents(ids);

        assertThat(out.get(a)).isEqualTo(new EventSalesFigures(5, 200, 4000L));
        assertThat(out.get(b)).isEqualTo(new EventSalesFigures(1, 80, 1000L));
        assertThat(out.get(c)).isEqualTo(EventSalesFigures.EMPTY);

        verify(tiers, times(1)).sumSoldAndQuantityByEventIds(ids);
        verify(orders, times(1)).sumTotalMinorByEventIds(ids);
        verify(refunds, times(1)).sumSucceededRefundMinorByEventIds(ids);
        verify(tickets, times(1)).countRevokedInDisputedOrdersByEventIds(ids, DisputeWithholding.STATUSES);
        verify(disputes, times(1)).withholdingRowsByEventIds(ids, DisputeWithholding.STATUSES);
        verify(refunds, times(1)).sumSucceededAmountAndFeeByOrderIds(Set.of(orderB));
        verify(tiers, never()).sumSoldByEventId(any());
        verify(tiers, never()).sumQuantityByEventId(any());
        verify(orders, never()).sumTotalMinorByEventId(any());
        verify(refunds, never()).sumSucceededRefundMinorByEventId(any());
        verifyNoMoreInteractions(tiers, orders, refunds, disputes, tickets);
    }

    @Test
    void more_disputed_tickets_than_sold_clamps_sold_at_zero() {
        UUID a = UUID.randomUUID();
        List<UUID> ids = List.of(a);
        when(tiers.sumSoldAndQuantityByEventIds(ids)).thenReturn(rows(new Object[] {a, 1L, 50L}));
        when(tickets.countRevokedInDisputedOrdersByEventIds(ids, DisputeWithholding.STATUSES))
                .thenReturn(rows(new Object[] {a, 3L}));

        assertThat(sut.forEvents(ids).get(a).sold()).isZero();
    }

    @Test
    void refunds_and_withholding_above_gross_clamp_revenue_at_zero() {
        UUID a = UUID.randomUUID();
        List<UUID> ids = List.of(a);
        when(orders.sumTotalMinorByEventIds(ids)).thenReturn(rows(new Object[] {a, 1000L}));
        when(refunds.sumSucceededRefundMinorByEventIds(ids)).thenReturn(rows(new Object[] {a, 800L}));
        when(disputes.withholdingRowsByEventIds(ids, DisputeWithholding.STATUSES))
                .thenReturn(List.of(new DisputeOrderRow(a, UUID.randomUUID(), 500L, 0L, 500L)));

        assertThat(sut.forEvents(ids).get(a).revenueMinor()).isZero();
    }

    @Test
    void for_event_reads_the_single_id_through_the_grouped_queries() {
        UUID a = UUID.randomUUID();
        when(tiers.sumSoldAndQuantityByEventIds(List.of(a))).thenReturn(rows(new Object[] {a, 4L, 100L}));

        assertThat(sut.forEvent(a)).isEqualTo(new EventSalesFigures(4, 100, 0L));
    }
}
