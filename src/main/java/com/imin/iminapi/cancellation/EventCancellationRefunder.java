package com.imin.iminapi.cancellation;

import com.imin.iminapi.cancellation.CancellationItemState.Derived;
import com.imin.iminapi.cancellation.CancellationItemState.Facts;
import com.imin.iminapi.cancellation.EventCancellationRepository.ItemFacts;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundRequestRepository;
import com.imin.iminapi.refund.RefundService;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.stripe.StripeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Moves one order of a cancelled event one step: claim, re-derive from live facts, refund what is owed.
 * Not transactional: each write commits on its own, and the refund row (committed by RefundAttemptStore
 * before Stripe is called) is the money marker, so a lost item write re-derives REFUNDED next pass.
 */
@Component
public class EventCancellationRefunder {

    private static final Logger log = LoggerFactory.getLogger(EventCancellationRefunder.class);

    /** Above the refund call's 110 s ceiling; also the re-read delay of an item still in flight. */
    static final Duration LEASE = Duration.ofMinutes(5);

    private final EventCancellationRepository items;
    private final RefundService refundService;
    private final RefundRequestRepository refundRequests;
    private final StripeProperties stripeProps;
    private final Clock clock;

    public EventCancellationRefunder(EventCancellationRepository items, RefundService refundService,
                                     RefundRequestRepository refundRequests, StripeProperties stripeProps,
                                     Clock clock) {
        this.items = items;
        this.refundService = refundService;
        this.refundRequests = refundRequests;
        this.stripeProps = stripeProps;
        this.clock = clock;
    }

    public void process(UUID orderId) {
        Instant now = now();
        if (!items.claim(orderId, now, now.plus(LEASE))) return;
        ItemFacts f = items.loadFacts(orderId).orElse(null);
        if (f == null) return;

        Derived derived = CancellationItemState.derive(facts(f, f.attempts()));
        if (derived.state() != CancellationItemState.PENDING) {
            settle(f, derived, null, now);
            return;
        }

        // The buyer hears of the cancel before any refund (a refund can succeed at once and confirm itself first);
        // a notice still being retried holds the refund until it is sent or has failed for good.
        if (!f.noticeStarted()) {
            items.releaseClaim(orderId);
            return;
        }

        // One remaining debt keeps one key: a refusal frees it for the retry, a failed refund changes the sum.
        String key = "event-cancel:" + f.eventId() + ":" + f.activeRefundedMinor();
        Refund refund;
        try {
            refund = refundService.createCancellationRefund(orderId, f.cancelledByUserId(), key);
        } catch (ApiException e) {
            switch (e.code()) {
                case REFUND_IN_PROGRESS -> settle(f, Derived.of(CancellationItemState.IN_PROGRESS), null, now);
                case ORDER_DISPUTED -> settle(f, Derived.of(CancellationItemState.WAITING_DISPUTE), null, now);
                case STRIPE_REFUND_FAILED, ORDER_NOT_REFUNDABLE -> refusedOrRaced(f, e.getMessage(), now);
                // Another refund claimed a ticket meanwhile; the next pass re-derives from its row.
                case TICKET_ALREADY_REFUNDED -> log.info("[cancellation] order {} raced another refund", orderId);
                default -> throw e;
            }
            return;
        }

        RefundStatus status = refund.getStatus();
        if (status == RefundStatus.SUCCEEDED) {
            settle(f, Derived.of(CancellationItemState.REFUNDED), refund.getId(), now);
        } else if (status == RefundStatus.PENDING) {
            settle(f, Derived.of(CancellationItemState.TRIGGERED), refund.getId(), now);
        } else if (status == RefundStatus.REQUESTED) {
            settle(f, Derived.of(CancellationItemState.IN_PROGRESS), refund.getId(), now);
        } else {
            // Stripe returned a failed or canceled refund object: no money moved, the claims are released.
            refused(f, "Stripe refund " + status.name().toLowerCase() + ": " + refund.getFailureMessage(), now);
        }
    }

    /** A refund another path made meanwhile ("Nothing left to refund") settles the item; it is not a refusal. */
    private void refusedOrRaced(ItemFacts f, String error, Instant now) {
        ItemFacts fresh = items.loadFacts(f.orderId()).orElse(f);
        Derived derived = CancellationItemState.derive(facts(fresh, fresh.attempts()));
        if (derived.state() != CancellationItemState.PENDING) {
            settle(f, derived, null, now);
            return;
        }
        refused(f, error, now);
    }

    private void refused(ItemFacts f, String error, Instant now) {
        int attempts = items.recordRefusal(f.orderId(), error, now);
        log.warn("[cancellation] refund refused orderId={} attempt={} — {}", f.orderId(), attempts, error);
        Derived next = CancellationItemState.derive(facts(f, attempts));
        if (next.state() == CancellationItemState.NEEDS_ATTENTION) settle(f, next, null, now);
    }

    private void settle(ItemFacts f, Derived derived, UUID refundId, Instant now) {
        CancellationItemState state = derived.state();
        String error = state == CancellationItemState.NEEDS_ATTENTION && !"refused".equals(derived.reason())
                ? derived.reason() : null;
        items.writeState(f.orderId(), state, refundId, error, now);
        if (state == CancellationItemState.NEEDS_ATTENTION && f.state() != CancellationItemState.NEEDS_ATTENTION) {
            log.error("[CANCELLATION_REFUND_ATTENTION] orderId={} eventId={} reason={}",
                    f.orderId(), f.eventId(), derived.reason());
        }
        if (state == CancellationItemState.TRIGGERED || state == CancellationItemState.REFUNDED) {
            UUID closing = refundId != null ? refundId : items.latestActiveRefundId(f.orderId()).orElse(null);
            closeRequests(f, closing, "event_cancelled", now);
        } else if (state != CancellationItemState.PENDING && state != CancellationItemState.IN_PROGRESS) {
            // No cancellation refund will come for now; imin, the bank or the dispute settles it, never the organizer.
            closeRequests(f, null, "event_cancelled:" + state.wire(), now);
        }
    }

    private void closeRequests(ItemFacts f, UUID refundId, String note, Instant now) {
        int closed = refundRequests.closePendingForCancellation(f.orderId(), refundId, note, now);
        if (closed > 0) log.info("[cancellation] closed {} pending refund request(s) on order {} ({})", closed, f.orderId(), note);
    }

    private Facts facts(ItemFacts f, int attempts) {
        boolean modeMatches = f.testMode() != stripeProps.isLiveKey();
        boolean hasPayment = f.paymentIntentId() != null && !f.paymentIntentId().isBlank();
        return new Facts(modeMatches, f.totalMinor(), hasPayment, f.openDisputes(), f.lostDisputedMinor(),
                f.totalMinor() - f.activeRefundedMinor(), f.hasRequestedRefund(), f.hasPendingRefund(), attempts);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
