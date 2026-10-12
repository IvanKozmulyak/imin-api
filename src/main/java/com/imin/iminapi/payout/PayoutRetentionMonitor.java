package com.imin.iminapi.payout;

import com.imin.iminapi.dispute.DisputeWithholding;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.settlement.SettlementRate;
import com.imin.iminapi.stripe.StripeProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Track B (manual payouts) Phase 2 — stranded-funds / retention-deadline monitor
 * (plan §7, a SHIP REQUIREMENT, not deferred).
 *
 * <p>Once an account is flipped to a MANUAL payout schedule, Stripe requires funds be
 * disbursed within a retention window (~90 days most countries). The Phase-1 backfill
 * flips balances to manual on deploy, which STARTS that clock immediately for every
 * existing eligible org — so this monitor must be live alongside the payout job, not
 * added later. If an event's funds never become payable (org disabled, dispute frozen,
 * a persistently-failing payout) they can strand past the window.
 *
 * <p>Each day it finds events that ended more than {@link #RETENTION_BOUND} ago, carry a
 * positive payout net (settled stake after refunds, less the organizer's share of live
 * disputes, as the payout job computes it), belong to a manual-schedule eligible org, and
 * have NO {@code PAID} payout run — i.e. funds the org is likely still holding past the
 * safe bound — and logs a WARN alert per event so ops can force a payout before Stripe's
 * deadline. A CANCELLED event gets its own HELD FUNDS message instead: the payout job holds
 * it, so ops refund or release. It moves no money and writes nothing; it is a read-only alarm.
 *
 * <p><b>Inert when the flag is off:</b> returns at the first line when
 * {@code !props.isPayoutScheduleManual()}.
 */
@Component
public class PayoutRetentionMonitor {

    private static final Logger log = LoggerFactory.getLogger(PayoutRetentionMonitor.class);

    private static final int BATCH_SIZE = 200;
    /**
     * 75 days — a ~15-day margin under the common 90-day Stripe retention window, so
     * ops have time to act before funds strand. Tune alongside
     * {@code STRIPE_PAYOUT_BUFFER_DAYS} if a market's window differs.
     */
    private static final Duration RETENTION_BOUND = Duration.ofDays(75);

    private final StripeProperties props;
    private final EventRepository events;
    private final OrderRepository orders;
    private final DisputeWithholding disputeWithholding;
    private final Clock clock;

    public PayoutRetentionMonitor(StripeProperties props,
                                  EventRepository events,
                                  OrderRepository orders,
                                  DisputeWithholding disputeWithholding,
                                  Clock clock) {
        this.props = props;
        this.events = events;
        this.orders = orders;
        this.disputeWithholding = disputeWithholding;
        this.clock = clock;
    }

    /**
     * One stranded event. {@code netMinor} is in {@code currency}: the settlement currency when every
     * order is stamped with what Stripe settled, else the event currency at presentment ({@code approximate}).
     * Orders settled in more than one currency have no single figure: {@code mixedCurrencies} is true and
     * {@code currency} is null. {@code cancelled} events are held by the payout job, so ops refund or
     * release instead of forcing a payout.
     */
    record StrandedEvent(UUID eventId, UUID orgId, Instant endsAt, long netMinor, String currency,
                         long unstampedOrders, boolean approximate, boolean mixedCurrencies, boolean cancelled) {}

    @Scheduled(cron = "0 30 3 * * *", zone = "Europe/Amsterdam")
    @SchedulerLock(name = "PayoutRetentionMonitor.scan", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void scan() {
        if (!props.isPayoutScheduleManual()) return;   // inert when off

        List<StrandedEvent> stranded = findStranded(clock.instant(), BATCH_SIZE);
        for (StrandedEvent s : stranded) {
            String net = s.mixedCurrencies()
                    ? "net=mixed currencies (orders settled in more than one currency)"
                    : "net=" + (s.approximate() ? "≈" : "") + s.netMinor() + " " + s.currency()
                            + " (" + s.unstampedOrders() + " unstamped orders)";
            if (s.cancelled()) {
                // Held on purpose by the payout job; a payout is the wrong fix, refunds or a release are.
                log.warn("[payout-retention] HELD FUNDS: event {} org {} is CANCELLED, ended {} (>{} d ago), {} "
                                + "and has no PAID payout run; refund buyers or release the funds before Stripe's "
                                + "retention window closes",
                        s.eventId(), s.orgId(), s.endsAt(), RETENTION_BOUND.toDays(), net);
                continue;
            }
            log.warn("[payout-retention] STRANDED FUNDS: event {} org {} ended {} (>{} d ago) {} "
                            + "has no PAID payout run — force a payout before Stripe's retention window closes",
                    s.eventId(), s.orgId(), s.endsAt(), RETENTION_BOUND.toDays(), net);
        }
        if (!stranded.isEmpty()) {
            log.warn("[payout-retention] scan flagged {} event(s) with funds past the {}-day retention bound",
                    stranded.size(), RETENTION_BOUND.toDays());
        }
    }

    /** Every candidate page in {@code (endsAt, id)} order, kept when its payout net is positive. */
    List<StrandedEvent> findStranded(Instant now, int pageSize) {
        Instant retentionCutoff = now.minus(RETENTION_BOUND);
        List<StrandedEvent> out = new ArrayList<>();
        Instant afterEndsAt = EventRepository.FIRST_ENDS_AT;
        UUID afterId = EventRepository.FIRST_ID;
        while (true) {
            List<Event> page = events.findRetentionMonitorCandidatesAfter(
                    retentionCutoff, afterEndsAt, afterId, PageRequest.of(0, pageSize));
            for (Event e : page) {
                StrandedEvent s = stranded(e);
                if (s != null) out.add(s);
            }
            if (page.size() < pageSize) return out;
            Event last = page.get(page.size() - 1);
            afterEndsAt = last.getEndsAt();
            afterId = last.getId();
        }
    }

    /** The payout job's step-2 net, with unstamped orders at the identity rate; null when nothing is owed. */
    private StrandedEvent stranded(Event e) {
        List<OrderSettlementRow> rows = orders.settlementRowsByEventId(e.getId());
        long stake = 0L;
        long unstamped = 0L;
        Set<String> currencies = new HashSet<>();
        for (OrderSettlementRow r : rows) {
            boolean stamped = r.settlementCurrency() != null
                    && r.settlementGrossMinor() != null && r.settlementFeeMinor() != null;
            if (stamped) {
                currencies.add(r.settlementCurrency().toLowerCase(Locale.ROOT));
            } else {
                unstamped++;
            }
            stake += new SettlementRate(r.totalMinor(), r.feeMinor(),
                    stamped ? r.settlementGrossMinor() : r.totalMinor(),
                    stamped ? r.settlementFeeMinor() : r.feeMinor())
                    .stake(r.refundedMinor(), r.feeRefundedMinor());
        }
        long net = Math.max(0L, stake - disputeWithholding.organizerShareLiveMinor(e.getId()));
        if (net <= 0L) return null;
        boolean cancelled = e.getStatus() == EventStatus.CANCELLED;
        if (currencies.size() > 1) {
            return new StrandedEvent(e.getId(), e.getOrgId(), e.getEndsAt(), net, null, unstamped, true, true,
                    cancelled);
        }
        boolean exact = unstamped == 0L && currencies.size() == 1;
        String currency = exact ? currencies.iterator().next() : e.getCurrency();
        return new StrandedEvent(e.getId(), e.getOrgId(), e.getEndsAt(), net, currency, unstamped, !exact, false,
                cancelled);
    }
}
