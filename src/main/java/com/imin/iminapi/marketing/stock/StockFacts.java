package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertReason;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a stock alert quotes about one tier, from stored rows (left = quantity − reserved − sold, all-in prices, close),
 * plus the pure rules the evaluator, review and the send guard share. {@code checkedAt} is when they were read.
 */
public record StockFacts(UUID tierId, String tierName, int remaining, int quantity, int threshold,
                         long priceAllInMinor, UUID nextTierId, String nextTierName, Long nextPriceAllInMinor,
                         Instant closesAt, Instant checkedAt, String timezone, String currency) {

    /** The dashboard's "almost gone" rule (imin-webapp almostGone.ts): a fifth of the tier, rounded up, at most 30. */
    public static final int AUTO_DIVISOR = 5;
    public static final int AUTO_MAX = 30;

    /** One tier as the reader saw it. */
    public record Tier(UUID id, String name, boolean enabled, int quantity, int remaining, Instant saleStartsAt,
                       Instant saleClosesAt, int sortOrder, long priceAllInMinor) {}

    /** An event's tiers at {@code now} in sort order; {@code eventOnSale}: live, public, unsuspended, in its window. */
    public record Snapshot(boolean eventOnSale, Instant eventSaleClosesAt, String timezone, String currency,
                           List<Tier> tiers, Instant now) {
        public Snapshot {
            tiers = List.copyOf(tiers);
        }

        public Optional<Tier> tier(UUID id) {
            return tiers.stream().filter(t -> t.id().equals(id)).findFirst();
        }
    }

    /** min(30, ceil(quantity / 5)) in integers; a tier with no quantity gets 0, so it never qualifies. */
    public static int autoThreshold(int quantity) {
        if (quantity <= 0) return 0;
        return Math.min(AUTO_MAX, (quantity + AUTO_DIVISOR - 1) / AUTO_DIVISOR);
    }

    /** The organizer's fixed number can only lower the tier's auto threshold, never raise it. */
    public static int threshold(Integer fixed, int quantity) {
        int auto = autoThreshold(quantity);
        return fixed != null ? Math.min(fixed, auto) : auto;
    }

    /** Why "last tickets" is not true of the tier now (a {@link StockAlertReason}), or empty when it is. */
    public static Optional<String> lastTicketsBlocker(Snapshot s, UUID tierId, int threshold) {
        Optional<String> common = unavailable(s, tierId);
        if (common.isPresent()) return common;
        Tier t = s.tier(tierId).orElseThrow();
        // Bounded here too, so a threshold stored under an older rule can never quote "last tickets" on a full tier.
        if (t.remaining() >= t.quantity()) return Optional.of(StockAlertReason.MORE_LEFT);
        if (t.remaining() > Math.min(threshold, autoThreshold(t.quantity()))) return Optional.of(StockAlertReason.MORE_LEFT);
        return Optional.empty();
    }

    /**
     * Why "the price goes up" is not true now, or empty: the tier closes before the event and the one cheapest tier on
     * sale at that moment costs more all-in. A null {@code leadHours} skips the lead window (at send).
     */
    public static Optional<String> priceUpBlocker(Snapshot s, UUID tierId, Integer leadHours) {
        Optional<String> common = unavailable(s, tierId);
        if (common.isPresent()) return common;
        Tier t = s.tier(tierId).orElseThrow();
        Instant change = t.saleClosesAt();
        if (change == null) return Optional.of(StockAlertReason.DEADLINE_MOVED);
        // The event's own sales end first: no later tier can be bought, so nothing goes up.
        if (s.eventSaleClosesAt() != null && !s.eventSaleClosesAt().isAfter(change)) {
            return Optional.of(StockAlertReason.NO_NEXT_TIER);
        }
        if (leadHours != null && s.now().isBefore(change.minus(Duration.ofHours(leadHours)))) {
            return Optional.of(StockAlertReason.DEADLINE_MOVED);
        }
        List<Tier> cheapest = cheapestAt(s, t, change);
        if (cheapest.isEmpty()) return Optional.of(StockAlertReason.NO_NEXT_TIER);
        if (cheapest.get(0).priceAllInMinor() <= t.priceAllInMinor()) return Optional.of(StockAlertReason.NEXT_NOT_HIGHER);
        // Two tiers share the cheapest price after the change: the email could not name the next tier truthfully.
        if (cheapest.size() > 1) return Optional.of(StockAlertReason.NEXT_TIER_UNCLEAR);
        return Optional.empty();
    }

    /** The rule for {@code kind}; price_up uses {@code leadHours}, last_tickets {@code threshold}. */
    public static Optional<String> blocker(Snapshot s, StockAlertKind kind, UUID tierId, int threshold,
                                           Integer leadHours) {
        return kind == StockAlertKind.LAST_TICKETS
                ? lastTicketsBlocker(s, tierId, threshold)
                : priceUpBlocker(s, tierId, leadHours);
    }

    /** A quoted change moment or all-in price the fresh read no longer matches; a falling count stays true "as of". */
    public static Optional<String> frozenMismatch(StockAlertKind kind, StockFacts frozen, StockFacts fresh) {
        if (kind != StockAlertKind.PRICE_UP) return Optional.empty();
        if (!Objects.equals(frozen.closesAt(), fresh.closesAt())) return Optional.of(StockAlertReason.DEADLINE_MOVED);
        if (frozen.priceAllInMinor() != fresh.priceAllInMinor()
                || !Objects.equals(frozen.nextTierId(), fresh.nextTierId())
                || !Objects.equals(frozen.nextPriceAllInMinor(), fresh.nextPriceAllInMinor())) {
            return Optional.of(StockAlertReason.PRICE_CHANGED);
        }
        return Optional.empty();
    }

    /** The facts an email of {@code kind} quotes for the tier; the tier must exist in the snapshot. */
    public static StockFacts of(Snapshot s, StockAlertKind kind, UUID tierId, int threshold) {
        Tier t = s.tier(tierId).orElseThrow();
        Tier next = null;
        Instant closesAt;
        if (kind == StockAlertKind.PRICE_UP) {
            closesAt = t.saleClosesAt();
            next = nextAt(s, t).orElse(null);
        } else {
            closesAt = earlier(t.saleClosesAt(), s.eventSaleClosesAt());
        }
        return new StockFacts(t.id(), t.name(), t.remaining(), t.quantity(), threshold, t.priceAllInMinor(),
                next == null ? null : next.id(), next == null ? null : next.name(),
                next == null ? null : next.priceAllInMinor(), closesAt, s.now(), s.timezone(), s.currency());
    }

    /** The one cheapest other tier on sale when {@code t} stops selling; empty when there is none or a tie. */
    public static Optional<Tier> nextAt(Snapshot s, Tier t) {
        if (t.saleClosesAt() == null) return Optional.empty();
        List<Tier> cheapest = cheapestAt(s, t, t.saleClosesAt());
        return cheapest.size() == 1 ? Optional.of(cheapest.get(0)) : Optional.empty();
    }

    /** Every other tier on sale at {@code at} sharing the lowest all-in price. */
    private static List<Tier> cheapestAt(Snapshot s, Tier t, Instant at) {
        List<Tier> onSale = s.tiers().stream().filter(o -> !o.id().equals(t.id()) && onSaleAt(s, o, at)).toList();
        long min = onSale.stream().mapToLong(Tier::priceAllInMinor).min().orElse(Long.MAX_VALUE);
        return onSale.stream().filter(o -> o.priceAllInMinor() == min).toList();
    }

    // Buyable at `at`: enabled, started, not closed by then, the event still selling then, and stock left now.
    private static boolean onSaleAt(Snapshot s, Tier o, Instant at) {
        if (!o.enabled() || o.remaining() <= 0) return false;
        if (o.saleStartsAt() != null && o.saleStartsAt().isAfter(at)) return false;
        if (o.saleClosesAt() != null && !o.saleClosesAt().isAfter(at)) return false;
        return s.eventSaleClosesAt() == null || s.eventSaleClosesAt().isAfter(at);
    }

    /** Why a buyer cannot take the tier now (event, switch, window, stock in TierAvailability's order), or empty. */
    public static Optional<String> unavailable(Snapshot s, UUID tierId) {
        if (!s.eventOnSale()) return Optional.of(StockAlertReason.EVENT_NOT_ON_SALE);
        Tier t = s.tier(tierId).orElse(null);
        if (t == null || !t.enabled()) return Optional.of(StockAlertReason.TIER_OFF);
        if (t.saleStartsAt() != null && s.now().isBefore(t.saleStartsAt())) return Optional.of(StockAlertReason.TIER_OFF);
        if (t.saleClosesAt() != null && !s.now().isBefore(t.saleClosesAt())) {
            return Optional.of(StockAlertReason.DEADLINE_PASSED);
        }
        if (t.remaining() <= 0) return Optional.of(StockAlertReason.SOLD_OUT);
        return Optional.empty();
    }

    private static Instant earlier(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }
}
