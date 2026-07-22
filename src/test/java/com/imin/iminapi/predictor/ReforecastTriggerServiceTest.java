package com.imin.iminapi.predictor;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.ReforecastTrigger;
import com.imin.iminapi.predictor.service.ReforecastMilestones;
import com.imin.iminapi.predictor.service.ReforecastService;
import com.imin.iminapi.predictor.service.ReforecastTriggerService;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 2 triggers (86cav479j): leading-edge burst debounce and the event-level 25/50/75%
 * milestone-crossing gate on the fulfilment path.
 */
class ReforecastTriggerServiceTest {

    /** A clock the test advances by hand. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
    }

    private final ReforecastService reforecast = mock(ReforecastService.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final TicketTierRepository tiers = mock(TicketTierRepository.class);
    private final PredictorProperties props = new PredictorProperties(); // debounce 60s default
    private final MutableClock clock = new MutableClock();

    private final ReforecastTriggerService sut =
            new ReforecastTriggerService(reforecast, orders, tiers, props, clock);

    private final UUID eventId = UUID.randomUUID();

    // ---- pure crossing math ----------------------------------------------------

    @Test
    void newlyCrossedReturnsOnlyThresholdsNewlyPassed() {
        assertThat(ReforecastMilestones.newlyCrossed(0, 60, 200)).containsExactly(25);      // 0% → 30%
        assertThat(ReforecastMilestones.newlyCrossed(60, 61, 200)).isEmpty();               // 30% → 30%
        assertThat(ReforecastMilestones.newlyCrossed(40, 160, 200)).containsExactly(25, 50, 75); // 20% → 80%
        assertThat(ReforecastMilestones.newlyCrossed(0, 10, 0)).isEmpty();                  // no capacity
    }

    // ---- debounce --------------------------------------------------------------

    @Test
    void requestRecomputeCoalescesBurstsWithinWindow() {
        boolean a = sut.requestRecompute(eventId, ReforecastTrigger.MILESTONE); // t=0 → runs
        clock.advanceSeconds(30);
        boolean b = sut.requestRecompute(eventId, ReforecastTrigger.MILESTONE); // t=30 → coalesced
        clock.advanceSeconds(31);
        boolean c = sut.requestRecompute(eventId, ReforecastTrigger.MILESTONE); // t=61 → runs

        assertThat(a).isTrue();
        assertThat(b).isFalse();
        assertThat(c).isTrue();
        verify(reforecast, times(2)).recompute(eq(eventId), any());
    }

    // ---- milestone hook --------------------------------------------------------

    @Test
    void ticketsIssuedRecomputesOnlyOnAnEventLevelCrossing() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setEventId(eventId);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(tiers.sumQuantityByEventId(eventId)).thenReturn(200);
        when(tiers.sumSoldByEventId(eventId)).thenReturn(60);     // 30%
        when(reforecast.lastForecastSold(eventId)).thenReturn(0); // was 0% → crosses 25

        sut.onTicketsIssued(new TicketsIssuedEvent(orderId));
        verify(reforecast, times(1)).recompute(eq(eventId), eq(ReforecastTrigger.MILESTONE));
    }

    @Test
    void ticketsIssuedDoesNotRecomputeWithoutACrossing() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setEventId(eventId);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(tiers.sumQuantityByEventId(eventId)).thenReturn(200);
        when(tiers.sumSoldByEventId(eventId)).thenReturn(61);      // 30%
        when(reforecast.lastForecastSold(eventId)).thenReturn(60); // still 30% → no new threshold

        sut.onTicketsIssued(new TicketsIssuedEvent(orderId));
        verify(reforecast, never()).recompute(any(), any());
    }
}
