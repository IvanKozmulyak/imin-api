package com.imin.iminapi.dispute;

import com.imin.iminapi.refund.RefundOrderSums;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rows the database cannot produce or show: a dispute whose order id finds no order row (the FK on
 * disputes.order_id keeps it out), and how many refund reads a page of events costs.
 */
class DisputeWithholdingTest {

    DisputeRepository disputes = mock(DisputeRepository.class);
    RefundRepository refunds = mock(RefundRepository.class);
    DisputeWithholding sut = new DisputeWithholding(disputes, mock(TicketRepository.class), refunds);

    @Test
    void a_dispute_whose_order_is_missing_withholds_its_whole_amount() {
        UUID eventId = UUID.randomUUID();
        when(disputes.withholdingRowsByEventIds(List.of(eventId), DisputeWithholding.STATUSES))
                .thenReturn(List.of(new DisputeOrderRow(eventId, UUID.randomUUID(), null, null, 700L)));
        when(refunds.sumSucceededAmountAndFeeByOrderIds(any())).thenReturn(List.of());

        assertThat(sut.organizerShareMinor(eventId)).isEqualTo(700L);
        assertThat(sut.withheldMinor(eventId)).isEqualTo(700L);
    }

    /** One refunds query for the whole page, not one per event. */
    @Test
    void a_page_reads_refunds_once() {
        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();
        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();
        List<UUID> page = List.of(eventA, eventB);
        when(disputes.withholdingRowsByEventIds(page, DisputeWithholding.STATUSES)).thenReturn(List.of(
                new DisputeOrderRow(eventA, orderA, 1_149L, 149L, 1_149L),
                new DisputeOrderRow(eventB, orderB, 2_298L, 298L, 2_298L)));
        when(refunds.sumSucceededAmountAndFeeByOrderIds(any()))
                .thenReturn(List.of(new RefundOrderSums(orderB, 1_149L, 149L)));

        Map<UUID, Long> out = sut.withheldMinorByEvent(page);

        // B: 2298 disputed, capped at 2298 − 1149 refunded.
        assertThat(out).containsExactlyInAnyOrderEntriesOf(Map.of(eventA, 1_149L, eventB, 1_149L));
        verify(refunds, times(1)).sumSucceededAmountAndFeeByOrderIds(any());
        verify(refunds).sumSucceededAmountAndFeeByOrderIds(Set.of(orderA, orderB));
    }
}
