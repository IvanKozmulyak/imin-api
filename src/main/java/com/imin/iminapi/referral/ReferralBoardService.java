package com.imin.iminapi.referral;

import com.imin.iminapi.referral.dto.ReferralBoardResponse;
import com.imin.iminapi.referral.dto.ReferralPerkDto;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.analytics.NetOrderRevenue;
import com.imin.iminapi.util.OrderNumber;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The organizer's crew-link leaderboard for one event. Totals cover every LIVE referrer of the event; rows are the
 * same set, capped. Missed async unlocks are healed first, so the board never shows a buyer past the goal as locked.
 */
@Service
public class ReferralBoardService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final OrderRepository orders;
    private final ReferralPerkUnlockRepository unlocks;
    private final ReferralPerkService perks;
    private final TransactionTemplate readOnly;

    public ReferralBoardService(OrderRepository orders, ReferralPerkUnlockRepository unlocks,
                                ReferralPerkService perks, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.unlocks = unlocks;
        this.perks = perks;
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
    }

    public ReferralBoardResponse board(AuthPrincipal principal, UUID eventId, Integer limit) {
        perks.requireOwnEvent(eventId, principal);
        int capped = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(1, limit), MAX_LIMIT);
        // Its own committed transaction, before the read below.
        perks.evaluateEvent(eventId);
        return readOnly.execute(status -> read(eventId, capped));
    }

    private ReferralBoardResponse read(UUID eventId, int limit) {
        List<ReferralBoardResponse.Row> rows = orders.referrerRows(eventId, limit).stream()
                .map(r -> new ReferralBoardResponse.Row(
                        (UUID) r[0], OrderNumber.code((UUID) r[0]), (String) r[1], ((Number) r[2]).longValue(),
                        instant(r[3]), instant(r[4]), (String) r[5], (String) r[6],
                        r[7] == null ? null : ((Number) r[7]).intValue()))
                .toList();
        Object[] t = orders.referrerTotals(eventId).get(0);
        long rowCount = ((Number) t[0]).longValue();
        long referrers = ((Number) t[1]).longValue();
        long tickets = ((Number) t[2]).longValue();
        long net = NetOrderRevenue.sumByKey(orders.revenueRowsByReferrer(eventId)).values().stream()
                .mapToLong(Long::longValue).sum();
        var totals = new ReferralBoardResponse.Totals(tickets, net, referrers,
                unlocks.countByEventId(eventId), unlocks.countByEventIdAndGivenAtIsNotNull(eventId));
        ReferralPerkDto perk = perks.perkForOrganizer(eventId).map(ReferralPerkDto::from).orElse(null);
        return new ReferralBoardResponse(perks.perksEnabled(), perk, totals, rows, rowCount);
    }

    private static Instant instant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalStateException("Unexpected timestamp type " + v.getClass().getName());
    }
}
