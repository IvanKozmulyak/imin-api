package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.dto.dashboard.DashboardResponse.*;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.User;
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

@Service
public class DashboardService {

    private static final DateTimeFormatter ACTIVITY_TIME_FMT =
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final UserRepository users;
    private final OrderRepository orders;
    private final AuditLogRepository auditLogs;

    public DashboardService(EventRepository events, TicketTierRepository tiers, UserRepository users,
                            OrderRepository orders, AuditLogRepository auditLogs) {
        this.events = events;
        this.tiers = tiers;
        this.users = users;
        this.orders = orders;
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "dashboard",
            key = "T(java.lang.String).join('|', #p.orgId().toString(), #cyclePeriod.name(), #businessPeriod.name())")
    public DashboardResponse build(AuthPrincipal p, DashboardPeriod cyclePeriod, DashboardPeriod businessPeriod) {
        User u = users.findById(p.userId()).orElseThrow();
        var firstName = displayFirstName(u.getFirstName(), u.getEmail());

        Instant now = Instant.now();
        Optional<Event> next = events.findUpcomingLive(p.orgId(), now, PageRequest.of(0, 1)).stream().findFirst();
        Optional<Event> past = events.findRecentPast(p.orgId(), PageRequest.of(0, 1)).stream().findFirst();

        Now nowDto = next.map(e -> {
            int totalQty = tiers.sumQuantityByEventId(e.getId());
            int sold = tiers.sumSoldByEventId(e.getId());
            long revenue = orders.sumTotalMinorByEventId(e.getId());
            int pct = totalQty == 0 ? 0 : (int) Math.round(100.0 * sold / totalQty);
            int daysOut = (int) Duration.between(now, e.getStartsAt()).toDays();
            return new Now(summaryWithLiveMetrics(e, sold, revenue), pct, Math.max(0, daysOut), totalQty);
        }).orElse(new Now(null, 0, 0, 0));

        long activeCount = events.countLive(p.orgId());
        Cycle cycle = buildCycle(p, now, cyclePeriod, activeCount);

        LastEvent lastEvent = past.map(e -> {
            int capacity = tiers.sumQuantityByEventId(e.getId());
            int sold = tiers.sumSoldByEventId(e.getId());
            long revenue = orders.sumTotalMinorByEventId(e.getId());
            int avgTicket = sold == 0 ? 0 : (int) (revenue / sold);
            return new LastEvent(summaryWithLiveMetrics(e, sold, revenue),
                    new LastEventMetrics(sold, capacity, avgTicket, /* nps */ null));
        }).orElse(new LastEvent(null, new LastEventMetrics(0, 0, 0, null)));

        Business business = buildBusiness(p, now, businessPeriod);
        List<Activity> activity = recentActivity(p);

        return new DashboardResponse(new Greeting(firstName), nowDto, cycle, lastEvent,
                /* prediction */ null, business, activity);
    }

    private Cycle buildCycle(AuthPrincipal p, Instant now, DashboardPeriod period, long activeCount) {
        if (period.isAll()) {
            Object[] sums = orders.sumRevenueAndCountByOrgInWindow(p.orgId(), Instant.EPOCH, now).get(0);
            long rev = ((Number) sums[0]).longValue();
            long sold = ((Number) sums[1]).longValue();
            return new Cycle(period.wire(), rev, (int) sold, (int) activeCount, new Deltas(0, 0));
        }

        Duration d = period.duration();
        Instant since = now.minus(d);
        Instant priorSince = since.minus(d);

        Object[] current = orders.sumRevenueAndCountByOrgInWindow(p.orgId(), since, now).get(0);
        Object[] prior = orders.sumRevenueAndCountByOrgInWindow(p.orgId(), priorSince, since).get(0);

        long curRev = ((Number) current[0]).longValue();
        long curCnt = ((Number) current[1]).longValue();
        long priorRev = ((Number) prior[0]).longValue();
        long priorCnt = ((Number) prior[1]).longValue();

        return new Cycle(period.wire(), curRev, (int) curCnt, (int) activeCount,
                new Deltas(pctDelta(curRev, priorRev), pctDelta(curCnt, priorCnt)));
    }

    private Business buildBusiness(AuthPrincipal p, Instant now, DashboardPeriod period) {
        long revenue;
        int repeatRate;
        if (period.isAll()) {
            Object[] sums = orders.sumRevenueAndCountByOrgInWindow(p.orgId(), Instant.EPOCH, now).get(0);
            revenue = ((Number) sums[0]).longValue();
            repeatRate = repeatRatePct(orders.orderCountsByEmailSince(p.orgId(), Instant.EPOCH));
        } else {
            Instant since = now.minus(period.duration());
            Object[] sums = orders.sumRevenueAndCountByOrgInWindow(p.orgId(), since, now).get(0);
            revenue = ((Number) sums[0]).longValue();
            repeatRate = repeatRatePct(orders.orderCountsByEmailSince(p.orgId(), since));
        }
        return new Business(revenue, events.countPublished(p.orgId()), events.countPast(p.orgId()),
                /* audienceCount */ 0, repeatRate);
    }

    /**
     * Event.sold/revenueMinor columns are never written to — sales live in
     * TicketTier.sold and the orders table. Rebuild the summary with the
     * live values so the dashboard's Now and LastEvent cards aren't stuck at 0.
     */
    private static EventDto summaryWithLiveMetrics(Event e, int sold, long revenueMinor) {
        EventDto b = EventDto.summary(e);
        return new EventDto(b.id(), b.orgId(), b.name(), b.slug(),
                b.visibility(), b.status(), b.genre(), b.type(),
                b.startsAt(), b.endsAt(), b.timezone(), b.venue(),
                b.description(), b.posterUrl(), b.videoUrl(), b.djPhotoUrl(),
                sold, revenueMinor, b.currency(),
                b.onSaleAt(), b.saleClosesAt(),
                b.createdBy(), b.createdAt(), b.updatedAt(),
                b.publishedAt(), b.deletedAt(),
                null, null, null);
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

    /**
     * % of distinct buyers in window who placed >1 order. Returns 0 when no
     * orders, or when only single-order buyers exist (so the UI shows "0%
     * come back" instead of NaN/empty).
     */
    private static int repeatRatePct(List<Object[]> rows) {
        if (rows.isEmpty()) return 0;
        long total = rows.size();
        long repeat = rows.stream().filter(r -> ((Number) r[1]).longValue() > 1).count();
        return (int) Math.round(100.0 * repeat / total);
    }

    private static int pctDelta(long current, long prior) {
        if (prior == 0) return current == 0 ? 0 : 100;
        return (int) Math.round(100.0 * (current - prior) / prior);
    }

    private static String displayFirstName(String firstName, String email) {
        if (firstName != null && !firstName.isBlank()) return firstName.trim();
        if (email == null) return "";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
