package com.imin.iminapi.dispute;

import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A dispute whose order id finds no order row (the left join gives no total). The FK on
 * disputes.order_id keeps such a row out of the database, so the row is stubbed here.
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
}
