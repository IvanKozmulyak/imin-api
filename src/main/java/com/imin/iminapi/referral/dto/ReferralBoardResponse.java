package com.imin.iminapi.referral.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The event's crew-link leaderboard. {@code perksAvailable} mirrors the platform flag; {@code perk} is null when it
 * is off or the event never set one. {@code totals} cover the whole event (LIVE orders), {@code rows} are capped by
 * {@code limit} and {@code total} is the uncapped row count; {@code totals.referrers} counts distinct buyers, so one
 * buyer with two orders whose links sold is one referrer and two rows.
 */
public record ReferralBoardResponse(boolean perksAvailable, ReferralPerkDto perk, Totals totals, List<Row> rows,
                                    long total) {

    /** {@code ticketsViaLinks} = sum of every row's tickets; net revenue less SUCCEEDED refunds and LOST disputes. */
    public record Totals(long ticketsViaLinks, long netRevenueMinor, long referrers, long unlocked, long given) {}

    /** {@code unlockLabel}/{@code unlockThreshold}: what the buyer was promised at unlock; null when not unlocked. */
    public record Row(UUID orderId, String orderNumber, String email, long tickets, Instant unlockedAt,
                      Instant givenAt, String givenVia, String unlockLabel, Integer unlockThreshold) {}
}
