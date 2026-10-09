package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.dispute.DisputeWithholding;
import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.dto.dashboard.DashboardResponse.*;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.dto.event.EventSalesFigures;
import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.User;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DashboardService {

    private static final DateTimeFormatter ACTIVITY_TIME_FMT =
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final UserRepository users;
    private final OrderRepository orders;
    private final AuditLogRepository auditLogs;
    private final RefundRepository refunds;
    private final DisputeWithholding disputeWithholding;
    private final DashboardRevenue revenue;
    private final MembershipRepository memberships;

    public DashboardService(EventRepository events, TicketTierRepository tiers, UserRepository users,
                            OrderRepository orders, AuditLogRepository auditLogs,
                            RefundRepository refunds, DisputeWithholding disputeWithholding,
                            DashboardRevenue revenue, MembershipRepository memberships) {
        this.events = events;
        this.tiers = tiers;
        this.users = users;
        this.orders = orders;
        this.auditLogs = auditLogs;
        this.refunds = refunds;
        this.disputeWithholding = disputeWithholding;
        this.revenue = revenue;
        this.memberships = memberships;
    }

    /**
     * The cache key carries the USER id as well as the org id, and that is not
     * redundant: the payload opens with a {@link Greeting} built from the calling
     * user's own first name — or, when that is blank, the local part of their email
     * address (TeamService.invite creates members with an empty first name, so the
     * fallback is the common case for invited accounts). Keyed on the org alone, the
     * second person in an org to open the dashboard inside the 30s TTL was greeted
     * with a colleague's name, or with a colleague's email local part. Everything
     * else here is org-wide, so the cost is one copy of org-wide data per active
     * team member per period pair, well inside the 10_000-entry cap in CacheConfig.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "dashboard",
            key = "T(java.lang.String).join('|', #p.orgId().toString(), #p.userId().toString(), "
                    + "#cyclePeriod.name(), #businessPeriod.name())")
    public DashboardResponse build(AuthPrincipal p, DashboardPeriod cyclePeriod, DashboardPeriod businessPeriod) {
        User u = users.findById(p.userId()).orElseThrow();
        var firstName = displayFirstName(u.getFirstName(), u.getEmail());

        Instant now = Instant.now();
        Optional<Event> next = events.findUpcomingLive(p.orgId(), now, PageRequest.of(0, 1)).stream().findFirst();
        Optional<Event> past = events.findRecentPast(p.orgId(), PageRequest.of(0, 1)).stream().findFirst();

        Now nowDto = next.map(e -> {
            int totalQty = tiers.sumQuantityByEventId(e.getId());
            int sold = soldNetOfDisputes(e.getId());
            long revenue = revenueNetOfRefundsAndDisputes(e.getId());
            int pct = totalQty == 0 ? 0 : (int) Math.round(100.0 * sold / totalQty);
            int daysOut = (int) Duration.between(now, e.getStartsAt()).toDays();
            return new Now(summaryWithLiveMetrics(e, sold, totalQty, revenue), pct, Math.max(0, daysOut), totalQty);
        }).orElse(new Now(null, 0, 0, 0));

        long activeCount = events.countLive(p.orgId());
        Cycle cycle = buildCycle(p, now, cyclePeriod, activeCount);

        LastEvent lastEvent = past.map(e -> {
            int capacity = tiers.sumQuantityByEventId(e.getId());
            int sold = soldNetOfDisputes(e.getId());
            long eventRevenue = revenueNetOfRefundsAndDisputes(e.getId());
            return new LastEvent(summaryWithLiveMetrics(e, sold, capacity, eventRevenue),
                    new LastEventMetrics(sold, capacity, avgTicketMinor(e.getId()), /* nps */ null));
        }).orElse(new LastEvent(null, new LastEventMetrics(0, 0, null, null)));

        Business business = buildBusiness(p, now, businessPeriod);
        List<Activity> activity = recentActivity(p);

        return new DashboardResponse(new Greeting(firstName), nowDto, cycle, lastEvent,
                /* prediction */ null, business, activity);
    }

    /**
     * Per-event figures net of chargebacks, matching the event Overview and Sales tabs.
     * TicketTier.sold is untouched by dispute ingest, so the revoked tickets come off here.
     */
    private int soldNetOfDisputes(UUID eventId) {
        return Math.max(0, tiers.sumSoldByEventId(eventId)
                - disputeWithholding.disputedTicketCount(eventId));
    }

    /**
     * Gross less succeeded refunds less withheld chargebacks, clamped at 0 — the same
     * expression the Overview and Sales tabs use. Dropping the refund term here made the
     * org home read higher than Overview for any event that had ever refunded a ticket.
     */
    private long revenueNetOfRefundsAndDisputes(UUID eventId) {
        return Math.max(0L, orders.sumTotalMinorByEventId(eventId)
                - refunds.sumSucceededRefundMinorByEventId(eventId)
                - disputeWithholding.withheldMinor(eventId));
    }

    /** The event's net over its tickets not refunded or revoked, half up; null with no such ticket. */
    private Integer avgTicketMinor(UUID eventId) {
        long tickets = revenue.ticketsForEvent(eventId);
        return tickets == 0 ? null : (int) Math.round((double) revenue.netForEvent(eventId) / tickets);
    }

    private Cycle buildCycle(AuthPrincipal p, Instant now, DashboardPeriod period, long activeCount) {
        if (period.isAll()) {
            DashboardRevenue.Window w = revenue.forOrgWindow(p.orgId(), Instant.EPOCH, now);
            return new Cycle(period.wire(), w.netRevenueMinor(), (int) w.ticketsSold(), (int) activeCount,
                    new Deltas(null, null));
        }

        Duration d = period.duration();
        Instant since = now.minus(d);
        DashboardRevenue.Window cur = revenue.forOrgWindow(p.orgId(), since, now);
        DashboardRevenue.Window prior = revenue.forOrgWindow(p.orgId(), since.minus(d), since);

        return new Cycle(period.wire(), cur.netRevenueMinor(), (int) cur.ticketsSold(), (int) activeCount,
                new Deltas(pctDelta(cur.netRevenueMinor(), prior.netRevenueMinor()),
                        pctDelta(cur.ticketsSold(), prior.ticketsSold())));
    }

    private Business buildBusiness(AuthPrincipal p, Instant now, DashboardPeriod period) {
        Instant since = period.isAll() ? Instant.EPOCH : now.minus(period.duration());
        long total = revenue.forOrgWindow(p.orgId(), since, now).netRevenueMinor();
        return new Business(total, events.countPublished(p.orgId()), events.countPast(p.orgId()),
                memberships.countByOrgId(p.orgId()));
    }

    /**
     * The Now and LastEvent summaries carry the live figures computed above;
     * a tier-quantity sum of 0 is unknown capacity, so it goes out as null.
     */
    private static EventDto summaryWithLiveMetrics(Event e, int sold, int capacity, long revenueMinor) {
        return EventDto.summary(e,
                new EventSalesFigures(sold, capacity > 0 ? capacity : null, revenueMinor));
    }

    /** Top-5 audit-log rows for the right-rail "Activity" tile. */
    private List<Activity> recentActivity(AuthPrincipal p) {
        return auditLogs.findByOrgIdOrderByOccurredAtDesc(p.orgId(), PageRequest.of(0, 5)).stream()
                .map(this::toActivity)
                .toList();
    }

    private Activity toActivity(AuditLog a) {
        return new Activity(ACTIVITY_TIME_FMT.format(a.getOccurredAt()), a.getSummary());
    }

    /** Null when the prior window is empty: a percentage of nothing is not a number to show. */
    private static Integer pctDelta(long current, long prior) {
        if (prior == 0) return null;
        long pct = Math.round(100.0 * (current - prior) / prior);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, pct));
    }

    private static String displayFirstName(String firstName, String email) {
        if (firstName != null && !firstName.isBlank()) return firstName.trim();
        if (email == null) return "";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
