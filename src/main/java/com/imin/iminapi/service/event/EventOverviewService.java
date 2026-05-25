package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventOverviewResponse;
import com.imin.iminapi.dto.event.EventOverviewResponse.Metrics;
import com.imin.iminapi.dto.event.EventOverviewResponse.QuickAction;
import com.imin.iminapi.dto.event.EventOverviewResponse.RecentPurchase;
import com.imin.iminapi.dto.event.PredictionDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.PredictionRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EventOverviewService {

    private static final List<QuickAction> ACTIONS = List.of(
            new QuickAction("send_campaign", "✉️", "Send campaign to audience"),
            new QuickAction("comp_tickets", "🎟️", "Generate comp tickets"),
            new QuickAction("copy_link", "🔗", "Copy buyer link"),
            new QuickAction("qr_scanner", "📱", "Open QR scanner")
    );

    private static final int RECENT_LIMIT = 8;

    private final EventRepository events;
    private final PredictionRepository predictions;
    private final TicketTierRepository tiers;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final TicketRepository tickets;

    public EventOverviewService(EventRepository events,
                                PredictionRepository predictions,
                                TicketTierRepository tiers,
                                OrderRepository orders,
                                RefundRepository refunds,
                                TicketRepository tickets) {
        this.events = events;
        this.predictions = predictions;
        this.tiers = tiers;
        this.orders = orders;
        this.refunds = refunds;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public EventOverviewResponse overview(AuthPrincipal p, UUID id) {
        Event e = events.findActive(id).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");

        int daysOut = e.getStartsAt() == null ? 0
                : (int) Duration.between(Instant.now(), e.getStartsAt()).toDays();
        int capacity = tiers.sumQuantityByEventId(id);
        int sold = tiers.sumSoldByEventId(id);
        long gross = orders.sumTotalMinorByEventId(id);
        long refunded = refunds.sumSucceededRefundMinorByEventId(id);
        long revenueMinor = Math.max(0L, gross - refunded);

        Metrics m = new Metrics(sold, capacity, revenueMinor, e.getCurrency(), Math.max(0, daysOut));

        // Recent buyers: skip fully-refunded orders, and within each order count
        // only non-refunded tickets (so the tier breakdown + amount reflect what
        // the buyer actually still holds). Take the most-recent RECENT_LIMIT
        // surviving orders.
        List<RecentPurchase> recentPurchases = orders.findByEventIdOrderByCreatedAtDesc(id).stream()
                .map(o -> {
                    List<Ticket> live = tickets.findByOrderIdOrderByCreatedAtAsc(o.getId()).stream()
                            .filter(t -> !Ticket.STATE_REFUNDED.equals(t.getState()))
                            .toList();
                    return live.isEmpty() ? null : toRecentPurchase(o, live);
                })
                .filter(java.util.Objects::nonNull)
                .limit(RECENT_LIMIT)
                .toList();

        var prediction = predictions.findById(id).map(PredictionDto::from).orElse(null);
        return new EventOverviewResponse(m, recentPurchases, prediction, ACTIONS);
    }

    /**
     * Build a Recent Buyers row from the LIVE (non-refunded) tickets in the order.
     * {@code time} is an ISO-8601 instant string — the frontend formats it as a
     * relative phrase ("5m ago"). {@code name} is the buyer's email (orders have
     * no name field). {@code sub} is a tier breakdown of the live tickets plus
     * their summed price-at-purchase amount, e.g. {@code "GA × 2 · 45.00 EUR"}.
     *
     * <p>If some of the order's tickets have been refunded, this reflects only
     * what the buyer actually still holds — so the row's amount can be less than
     * {@code order.totalMinor}.
     */
    private static RecentPurchase toRecentPurchase(Order order, List<Ticket> liveTickets) {
        String currency = order.getCurrency() == null ? "" : order.getCurrency().toUpperCase(Locale.ROOT);

        Map<String, Long> byTier = liveTickets.stream()
                .collect(Collectors.groupingBy(
                        Ticket::getTierName,
                        LinkedHashMap::new,
                        Collectors.counting()));

        String tierBreakdown = byTier.entrySet().stream()
                .map(en -> en.getValue() == 1L ? en.getKey() : en.getKey() + " × " + en.getValue())
                .collect(Collectors.joining(", "));

        long amountMinor = liveTickets.stream().mapToLong(Ticket::getPriceMinor).sum();
        String amount = String.format(Locale.ROOT, "%.2f %s", amountMinor / 100.0, currency).trim();
        String sub = tierBreakdown.isEmpty() ? amount : tierBreakdown + " · " + amount;

        String time = order.getCreatedAt() == null ? "" : order.getCreatedAt().toString();
        return new RecentPurchase(time, order.getEmail(), sub);
    }
}
