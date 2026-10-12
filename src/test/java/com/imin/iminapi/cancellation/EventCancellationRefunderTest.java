package com.imin.iminapi.cancellation;

import com.imin.iminapi.cancellation.EventCancellationRepository.ItemFacts;
import com.imin.iminapi.refund.RefundRequestRepository;
import com.imin.iminapi.refund.RefundService;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A refund another path makes between this pass reading the facts and the refund call is a read-side race no
 * trigger can stage, so it is a unit test: "Nothing left to refund" then settles the item, never counts a refusal.
 */
class EventCancellationRefunderTest {

    private final EventCancellationRepository items = mock(EventCancellationRepository.class);
    private final RefundService refunds = mock(RefundService.class);
    private final RefundRequestRepository requests = mock(RefundRequestRepository.class);
    private final Instant now = Instant.parse("2026-11-03T23:30:00Z");
    private final EventCancellationRefunder refunder = new EventCancellationRefunder(items, refunds, requests,
            new StripeProperties(), Clock.fixed(now, ZoneOffset.UTC));

    private static ItemFacts facts(UUID orderId, long activeRefunded, boolean pendingRefund) {
        // Paid 1149 in test mode (the default key is not live), notice already sent, no dispute, no attempt yet.
        return new ItemFacts(orderId, UUID.randomUUID(), CancellationItemState.PENDING, 0, UUID.randomUUID(),
                1149, 149, true, "pi_1", 0, 0, activeRefunded, activeRefunded == 0 ? 0 : 149, false,
                pendingRefund, true);
    }

    @Test
    void nothingLeftBecauseAnotherRefundLanded_settlesTheItem_withoutCountingAnAttempt() {
        UUID orderId = UUID.randomUUID();
        when(items.claim(eq(orderId), any(), any())).thenReturn(true);
        when(items.loadFacts(orderId)).thenReturn(Optional.of(facts(orderId, 0, false)),
                Optional.of(facts(orderId, 1149, true)));
        when(refunds.createCancellationRefund(eq(orderId), any(), anyString())).thenThrow(
                new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE, "Nothing left to refund"));

        refunder.process(orderId);

        verify(items, never()).recordRefusal(any(), any(), any());
        verify(items).writeState(eq(orderId), eq(CancellationItemState.TRIGGERED), any(), any(), any());
    }

    @Test
    void aRefusalTheFactsDoNotExplain_isCountedAsAnAttempt() {
        UUID orderId = UUID.randomUUID();
        when(items.claim(eq(orderId), any(), any())).thenReturn(true);
        when(items.loadFacts(orderId)).thenReturn(Optional.of(facts(orderId, 0, false)));
        when(refunds.createCancellationRefund(eq(orderId), any(), anyString())).thenThrow(
                new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.STRIPE_REFUND_FAILED, "No such payment_intent"));

        refunder.process(orderId);

        verify(items).recordRefusal(eq(orderId), eq("No such payment_intent"), any());
    }
}
