package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.dto.dashboard.DashboardResponse.*;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class DashboardService {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final UserRepository users;

    public DashboardService(EventRepository events, TicketTierRepository tiers, UserRepository users) {
        this.events = events;
        this.tiers = tiers;
        this.users = users;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "dashboard", key = "#p.orgId().toString()")
    public DashboardResponse build(AuthPrincipal p) {
        User u = users.findById(p.userId()).orElseThrow();
        var firstName = firstWord(u.getName(), u.getEmail());

        Optional<Event> next = events.findUpcomingLive(p.orgId(), Instant.now(), PageRequest.of(0, 1)).stream().findFirst();
        Optional<Event> past = events.findRecentPast(p.orgId(), PageRequest.of(0, 1)).stream().findFirst();

        Now now = next.map(e -> {
            int totalQty = tiers.sumQuantityByEventId(e.getId());
            int pct = totalQty == 0 ? 0 : (int) Math.round(100.0 * e.getSold() / totalQty);
            int daysOut = (int) Duration.between(Instant.now(), e.getStartsAt()).toDays();
            return new Now(EventDto.summary(e), pct, Math.max(0, daysOut));
        }).orElse(new Now(null, 0, 0));

        long activeCount = events.countLive(p.orgId());

        // Cycle (30d): we don't yet have a purchases table to compute true window-based deltas.
        // V1 stub: report all-time aggregates labelled as "30d", deltas at 0%.
        Object[] sums = events.sumRevenueAndSold(p.orgId()).get(0);
        long totalRevenue = ((Number) sums[0]).longValue();
        long totalSold = ((Number) sums[1]).longValue();
        Cycle cycle = new Cycle("30d", totalRevenue, (int) totalSold, /* squadRatePct */ 0,
                (int) activeCount, new Deltas(0, 0));

        LastEvent lastEvent = past.map(e -> {
            int avgTicket = e.getSold() == 0 ? 0 : (int) (e.getRevenueMinor() / e.getSold());
            return new LastEvent(EventDto.summary(e),
                    new LastEventMetrics(e.getSold(), avgTicket, /* nps */ null));
        }).orElse(new LastEvent(null, new LastEventMetrics(0, 0, null)));

        Business business = new Business(totalRevenue,
                events.countPublished(p.orgId()), events.countPast(p.orgId()),
                /* audienceCount */ 0, /* repeatRatePct */ 0);

        return new DashboardResponse(new Greeting(firstName), now, cycle, lastEvent,
                /* prediction */ null, business, List.of());
    }

    private static String firstWord(String name, String email) {
        if (name != null && !name.isBlank()) {
            int sp = name.indexOf(' ');
            return sp > 0 ? name.substring(0, sp) : name;
        }
        if (email == null) return "";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
