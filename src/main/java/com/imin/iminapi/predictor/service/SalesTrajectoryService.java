package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.model.EventSalesDaily;
import com.imin.iminapi.predictor.repository.EventSalesDailyRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Materializes the daily sales trajectory (spec §6.2) and serves the normalized read.
 *
 * <p>Materialization is a scheduled read-side aggregation — it never touches the checkout
 * path. {@link #materialize(UUID)} recomputes one event's whole per-tier daily series from
 * the SOLD ticket set and REPLACES it (delete + reinsert), so it is idempotent: re-running
 * yields the same rows, and the job's first run backfills all history for free.
 *
 * <p>Day bucketing is done in Java in the event's timezone, never a SQL {@code date(...)}
 * (H2 and Postgres diverge on truncation).
 */
@Service
public class SalesTrajectoryService {

    private final EventRepository events;
    private final TicketRepository tickets;
    private final EventSalesDailyRepository daily;

    public SalesTrajectoryService(EventRepository events, TicketRepository tickets,
                                  EventSalesDailyRepository daily) {
        this.events = events;
        this.tickets = tickets;
        this.daily = daily;
    }

    /** One point on the normalized pacing curve. */
    public record NormalizedPoint(LocalDate salesDate, Integer daysToEvent,
                                  int cumulativeSold, double pctOfFinal) {}

    /**
     * The normalized sales curve (spec §6.2 / §7 Stage 1 input): % of final sales achieved
     * by each day, alongside days-to-event. {@code daysToEvent} is null when the event has
     * no start date. Empty when the event has no sales yet.
     */
    public record NormalizedCurve(UUID eventId, int finalTotal, List<NormalizedPoint> points) {}

    /**
     * Recompute and replace an event's daily trajectory from the SOLD ticket set.
     * No-op (clears any stale rows) when the event has no sold tickets.
     */
    @Transactional
    public void materialize(UUID eventId) {
        Event e = events.findById(eventId).orElse(null);
        ZoneId zone = e == null ? ZoneId.of("UTC") : resolveZone(e.getTimezone());

        // (tierId, day) -> daily count, in insertion order of first-seen tier for stable output.
        Map<UUID, TreeMap<LocalDate, Integer>> perTier = new LinkedHashMap<>();
        for (Object[] row : tickets.findSoldTierAndCreatedAt(eventId)) {
            UUID tierId = (UUID) row[0];
            Instant createdAt = (Instant) row[1];
            LocalDate day = createdAt.atZone(zone).toLocalDate();
            perTier.computeIfAbsent(tierId, k -> new TreeMap<>()).merge(day, 1, Integer::sum);
        }

        daily.deleteByEventId(eventId);
        if (perTier.isEmpty()) return;

        List<EventSalesDaily> rows = new ArrayList<>();
        for (Map.Entry<UUID, TreeMap<LocalDate, Integer>> tier : perTier.entrySet()) {
            int cumulative = 0;
            for (Map.Entry<LocalDate, Integer> d : tier.getValue().entrySet()) {
                cumulative += d.getValue();
                EventSalesDaily r = new EventSalesDaily();
                r.setEventId(eventId);
                r.setTierId(tier.getKey());
                r.setSalesDate(d.getKey());
                r.setDailySold(d.getValue());
                r.setCumulativeSold(cumulative);
                rows.add(r);
            }
        }
        daily.saveAll(rows);
    }

    /**
     * The normalized pacing curve for an event: for each day with sales, the cumulative
     * total ACROSS tiers, that total as a fraction of the final total (0..1), and the
     * days-to-event. Read-only projection over the materialized rows — this is the
     * "normalized form" §6.2 promises as a query rather than a second table.
     */
    @Transactional(readOnly = true)
    public NormalizedCurve normalizedCurve(UUID eventId) {
        List<EventSalesDaily> rows = daily.findByEventIdOrderBySalesDateAscTierIdAsc(eventId);

        // Collapse tiers → total sold per day.
        TreeMap<LocalDate, Integer> dailyTotal = new TreeMap<>();
        for (EventSalesDaily r : rows) {
            dailyTotal.merge(r.getSalesDate(), r.getDailySold(), Integer::sum);
        }

        Event e = events.findById(eventId).orElse(null);
        LocalDate eventDay = eventDay(e);

        int finalTotal = dailyTotal.values().stream().mapToInt(Integer::intValue).sum();
        List<NormalizedPoint> points = new ArrayList<>();
        int cumulative = 0;
        for (Map.Entry<LocalDate, Integer> d : dailyTotal.entrySet()) {
            cumulative += d.getValue();
            Integer daysToEvent = eventDay == null ? null
                    : (int) ChronoUnit.DAYS.between(d.getKey(), eventDay);
            double pct = finalTotal == 0 ? 0.0 : (double) cumulative / finalTotal;
            points.add(new NormalizedPoint(d.getKey(), daysToEvent, cumulative, pct));
        }
        return new NormalizedCurve(eventId, finalTotal, points);
    }

    private LocalDate eventDay(Event e) {
        if (e == null || e.getStartsAt() == null) return null;
        return e.getStartsAt().atZone(resolveZone(e.getTimezone())).toLocalDate();
    }

    private static ZoneId resolveZone(String raw) {
        if (raw == null || raw.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException ex) {
            return ZoneId.of("UTC");
        }
    }
}
