package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 7-day revenue histogram for the EventOverviewTab sales-velocity sparkline.
 * Buckets are calendar days in the event's timezone (today + 6 prior). Each
 * bucket holds the gross revenue (sum of {@code Order.totalMinor}) for orders
 * created that day. Refunds are NOT subtracted here — they are reflected in
 * the headline Revenue card on the same tab.
 */
@Service
public class EventVelocityService {

    public record VelocityResponse(List<Long> points) {}

    private static final int WINDOW_DAYS = 7;

    private final EventRepository events;
    private final OrderRepository orders;
    private final Clock clock;

    public EventVelocityService(EventRepository events, OrderRepository orders, Clock clock) {
        this.events = events;
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public VelocityResponse last7Days(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");

        ZoneId zone = (e.getTimezone() == null || e.getTimezone().isBlank())
                ? ZoneId.of("UTC")
                : ZoneId.of(e.getTimezone());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate start = today.minusDays(WINDOW_DAYS - 1L);
        Instant since = start.atStartOfDay(zone).toInstant();

        long[] buckets = new long[WINDOW_DAYS];
        for (Object[] row : orders.findCreatedAtAndTotalSince(eventId, since)) {
            Instant ts = (Instant) row[0];
            long amount = ((Number) row[1]).longValue();
            LocalDate day = ts.atZone(zone).toLocalDate();
            int dayIdx = (int) ChronoUnit.DAYS.between(start, day);
            if (dayIdx >= 0 && dayIdx < WINDOW_DAYS) {
                buckets[dayIdx] += amount;
            }
        }
        return new VelocityResponse(Arrays.stream(buckets).boxed().toList());
    }
}
