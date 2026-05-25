package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.refund.RefundRepository;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 7-day NET revenue histogram for the EventOverviewTab sales-velocity chart.
 * Buckets are calendar days in the event's timezone (today + 6 prior). Each
 * bucket = (gross orders created that day) − (SUCCEEDED refunds confirmed that
 * day), floored at 0 to avoid negative bars. The response also includes the
 * matching ISO-8601 date strings so the FE can label the x-axis.
 *
 * <p>Subtracting refunds aligns with the rule "refunded tickets are not
 * counted on any metric". A refund issued today on an old order reduces
 * today's bar (clean per-day cash-flow view).
 */
@Service
public class EventVelocityService {

    /**
     * @param points  daily NET revenue totals in minor units, oldest first, today last.
     * @param days    matching ISO-8601 local date strings in the event's timezone
     *                (e.g. {@code "2026-05-25"}). Same length as {@code points}.
     */
    public record VelocityResponse(List<Long> points, List<String> days) {}

    private static final int WINDOW_DAYS = 7;

    private final EventRepository events;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final Clock clock;

    public EventVelocityService(EventRepository events,
                                OrderRepository orders,
                                RefundRepository refunds,
                                Clock clock) {
        this.events = events;
        this.orders = orders;
        this.refunds = refunds;
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
            int dayIdx = bucketIndex(ts, zone, start);
            if (dayIdx >= 0 && dayIdx < WINDOW_DAYS) buckets[dayIdx] += amount;
        }

        for (Object[] row : refunds.findSucceededRefundUpdatedAtAndAmountSince(eventId, since)) {
            Instant ts = (Instant) row[0];
            long amount = ((Number) row[1]).longValue();
            int dayIdx = bucketIndex(ts, zone, start);
            if (dayIdx >= 0 && dayIdx < WINDOW_DAYS) buckets[dayIdx] -= amount;
        }

        List<Long> points = new ArrayList<>(WINDOW_DAYS);
        for (long v : buckets) points.add(Math.max(0L, v));   // floor at 0

        List<String> days = new ArrayList<>(WINDOW_DAYS);
        for (int i = 0; i < WINDOW_DAYS; i++) days.add(start.plusDays(i).toString());

        return new VelocityResponse(points, days);
    }

    private static int bucketIndex(Instant ts, ZoneId zone, LocalDate start) {
        LocalDate day = ts.atZone(zone).toLocalDate();
        return (int) ChronoUnit.DAYS.between(start, day);
    }
}
