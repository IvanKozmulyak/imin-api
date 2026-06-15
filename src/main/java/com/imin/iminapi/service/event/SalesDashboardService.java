package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.SalesDashboardResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds one snapshot of an event's live sales dashboard. Tiles and the tier
 * breakdown are summed from the same per-tier ticket aggregates so they
 * reconcile by construction; net revenue reuses the same order/refund repo
 * methods as {@code EventOverviewService} so the money figure agrees.
 */
@Service
public class SalesDashboardService {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final FunnelEventRepository funnel;

    public SalesDashboardService(EventRepository events,
                                 TicketTierRepository tiers,
                                 TicketRepository tickets,
                                 OrderRepository orders,
                                 RefundRepository refunds,
                                 FunnelEventRepository funnel) {
        this.events = events;
        this.tiers = tiers;
        this.tickets = tickets;
        this.orders = orders;
        this.refunds = refunds;
        this.funnel = funnel;
    }

    @Transactional(readOnly = true)
    public SalesDashboardResponse dashboard(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");

        List<SalesDashboardResponse.TierBreakdown> tierRows = buildTiers(eventId);

        int ticketsSold = tierRows.stream().mapToInt(SalesDashboardResponse.TierBreakdown::sold).sum();
        long grossRevenueMinor = tierRows.stream()
                .mapToLong(SalesDashboardResponse.TierBreakdown::grossRevenueMinor).sum();
        int checkedIn = tierRows.stream().mapToInt(SalesDashboardResponse.TierBreakdown::redeemed).sum();

        long gross = orders.sumTotalMinorByEventId(eventId);
        long refunded = refunds.sumSucceededRefundMinorByEventId(eventId);
        long netRevenueMinor = Math.max(0L, gross - refunded);

        int capacity = tiers.sumQuantityByEventId(eventId);
        double capacityPct = capacity > 0 ? (ticketsSold * 100.0 / capacity) : 0.0;
        double checkInRatePct = ticketsSold > 0 ? (checkedIn * 100.0 / ticketsSold) : 0.0;

        SalesDashboardResponse.Tiles tiles = new SalesDashboardResponse.Tiles(
                ticketsSold, netRevenueMinor, grossRevenueMinor, capacity,
                capacityPct, checkedIn, checkInRatePct);

        List<SalesDashboardResponse.TierBreakdown> topConverting = new ArrayList<>(tierRows);
        topConverting.sort(Comparator.comparingDouble(
                SalesDashboardResponse.TierBreakdown::sellThroughPct).reversed());

        SalesDashboardResponse.Funnel funnelDto = buildFunnel(eventId);

        return new SalesDashboardResponse(e.getCurrency(), tiles, tierRows, topConverting, funnelDto);
    }

    /**
     * Merge the per-tier ticket aggregates with the current tier list. Current
     * tiers (in sort order) appear even with zero sales; aggregates for a tier
     * that was later deleted are appended using the snapshotted tier_name so
     * the totals still reconcile.
     */
    private List<SalesDashboardResponse.TierBreakdown> buildTiers(UUID eventId) {
        Map<UUID, long[]> agg = new HashMap<>();   // tierId -> [sold, gross, redeemed]
        Map<UUID, String> aggName = new HashMap<>();
        for (Object[] row : tickets.tierAggregates(eventId)) {
            UUID tierId = (UUID) row[0];
            aggName.put(tierId, (String) row[1]);
            agg.put(tierId, new long[]{((Number) row[2]).longValue(),
                                       ((Number) row[3]).longValue(),
                                       ((Number) row[4]).longValue()});
        }

        List<SalesDashboardResponse.TierBreakdown> out = new ArrayList<>();
        Map<UUID, Boolean> seen = new LinkedHashMap<>();

        for (TicketTier t : tiers.findByEventIdOrderBySortOrderAsc(eventId)) {
            long[] a = agg.getOrDefault(t.getId(), new long[]{0, 0, 0});
            out.add(toBreakdown(t.getId().toString(), t.getName(),
                    (int) a[0], (int) a[2], a[1], t.getQuantity()));
            seen.put(t.getId(), true);
        }
        // Deleted tiers that still have sold tickets — include so totals reconcile.
        // Deterministic order (HashMap iteration isn't): by display name, then tierId.
        agg.entrySet().stream()
                .filter(en -> !seen.containsKey(en.getKey()))
                .sorted(Comparator
                        .comparing((Map.Entry<UUID, long[]> en) -> aggName.getOrDefault(en.getKey(), ""))
                        .thenComparing(en -> en.getKey().toString()))
                .forEach(en -> {
                    long[] a = en.getValue();
                    out.add(toBreakdown(en.getKey().toString(), aggName.get(en.getKey()),
                            (int) a[0], (int) a[2], a[1], 0));
                });
        return out;
    }

    private SalesDashboardResponse.TierBreakdown toBreakdown(
            String tierId, String name, int sold, int redeemed, long gross, int quantity) {
        double sellThroughPct = quantity > 0 ? (sold * 100.0 / quantity) : 0.0;
        return new SalesDashboardResponse.TierBreakdown(
                tierId, name, sold, redeemed, gross, quantity, sellThroughPct);
    }

    private SalesDashboardResponse.Funnel buildFunnel(UUID eventId) {
        Map<String, Long> byStage = new HashMap<>();
        for (Object[] row : funnel.countDistinctAnonByStage(eventId)) {
            byStage.put((String) row[0], ((Number) row[1]).longValue());
        }
        long pageViews = byStage.getOrDefault(FunnelEvent.STAGE_PAGE_VIEW, 0L);
        long checkoutStarts = byStage.getOrDefault(FunnelEvent.STAGE_CHECKOUT_START, 0L);
        long payments = orders.countByEventId(eventId);

        List<SalesDashboardResponse.Funnel.Stage> stages = List.of(
                new SalesDashboardResponse.Funnel.Stage("PAGE_VIEW", pageViews),
                new SalesDashboardResponse.Funnel.Stage("CHECKOUT_START", checkoutStarts),
                new SalesDashboardResponse.Funnel.Stage("PAYMENTS_COMPLETED", payments));

        List<SalesDashboardResponse.Funnel.DropOff> drops = List.of(
                dropOff("PAGE_VIEW", pageViews, "CHECKOUT_START", checkoutStarts),
                dropOff("CHECKOUT_START", checkoutStarts, "PAYMENTS_COMPLETED", payments));

        return new SalesDashboardResponse.Funnel(stages, drops);
    }

    private SalesDashboardResponse.Funnel.DropOff dropOff(String from, long fromN, String to, long toN) {
        long lost = Math.max(0L, fromN - toN);          // clamp: unit mismatch can make toN > fromN
        double lostPct = fromN > 0 ? (lost * 100.0 / fromN) : 0.0;
        return new SalesDashboardResponse.Funnel.DropOff(from, to, lost, lostPct);
    }
}
