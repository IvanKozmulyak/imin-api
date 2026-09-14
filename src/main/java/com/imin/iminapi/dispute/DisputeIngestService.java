package com.imin.iminapi.dispute;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.stripe.SettlementIngestService;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.util.Times;
import com.stripe.model.Charge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Turns a {@code charge.dispute.*} webhook into real consequences: the buyer's tickets stop
 * working, the organizer is told, and the payout math learns what it must not pay out.
 *
 * <p>Complements {@link SettlementIngestService#ingestDispute}, which only annotates the
 * settlements read-model for the Payouts UI. This one owns the {@code disputes} registry.
 *
 * <p>Tickets use the EXISTING {@code revoked} state — already rejected at the door
 * ({@code TicketRedeemService}) and by the wallet ({@code WalletEligibility}) — so there is
 * no new state and no ticket migration. {@code tier.sold} is deliberately untouched: the sale
 * is contested, not reversed, and the seat is not going back on sale mid-dispute.
 *
 * <p>Runs {@code Propagation.MANDATORY} like {@link SettlementIngestService}: it is called
 * from the webhook's {@code @Transactional} dispatch inside the dedup gate, so a throw rolls
 * the dedup marker back with it and Stripe re-delivers.
 */
@Service
public class DisputeIngestService {

    private static final Logger log = LoggerFactory.getLogger(DisputeIngestService.class);

    private final DisputeRepository disputes;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final OrganizationRepository orgs;
    private final SettlementIngestService settlementIngest;
    private final ApplicationEventPublisher publisher;
    private final StripeProperties stripeProps;

    public DisputeIngestService(DisputeRepository disputes,
                                OrderRepository orders,
                                TicketRepository tickets,
                                OrganizationRepository orgs,
                                SettlementIngestService settlementIngest,
                                ApplicationEventPublisher publisher,
                                StripeProperties stripeProps) {
        this.disputes = disputes;
        this.orders = orders;
        this.tickets = tickets;
        this.orgs = orgs;
        this.settlementIngest = settlementIngest;
        this.publisher = publisher;
        this.stripeProps = stripeProps;
    }

    /**
     * Upsert the dispute row for {@code stripeDispute} and apply the ticket consequences of its
     * current state.
     *
     * @param stripeDispute    the deserialized Stripe Dispute (non-null).
     * @param connectedAccount {@code event.getAccount()}; the org fallback and the retry scope
     *                         for reading the disputed charge.
     * @param eventType        the canonical {@code charge.dispute.*} type.
     * @param eventAt          Stripe's {@code event.created}, for the out-of-order guard.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ingest(com.stripe.model.Dispute stripeDispute, String connectedAccount,
                       String eventType, Instant eventAt) {
        if (stripeDispute == null) return;

        // ponytail: the settlements ingest resolves the same charge a moment earlier, so a
        // dispute delivery costs two Stripe reads. Disputes are rare enough not to pay for
        // threading the resolved Charge through both call sites.
        Charge charge = settlementIngest.resolveDisputedCharge(stripeDispute, connectedAccount, eventType);
        String paymentIntentId = charge == null ? null : charge.getPaymentIntent();
        Order order = (paymentIntentId == null || paymentIntentId.isBlank())
                ? null
                : orders.findByStripePaymentIntentId(paymentIntentId).orElse(null);

        UUID orgId = resolveOrgId(order, charge, connectedAccount);
        if (orgId == null) {
            log.warn("[dispute] {} ({}) could not be attributed to an org — skipping (no row written)",
                    stripeDispute.getId(), eventType);
            return;
        }

        DisputeStatus status = resolveStatus(stripeDispute.getStatus(), eventType);

        Dispute row = disputes.findByStripeDisputeId(stripeDispute.getId()).orElse(null);
        if (row != null && isStale(row, eventAt, stripeDispute.getId(), eventType)) return;

        boolean firstSighting = row == null;
        DisputeStatus previous = firstSighting ? null : row.getStatus();
        // An existing row with no order is the race's orphan: nothing was ever revoked for it,
        // and once this delivery back-fills order_id neither attach path can reach it again.
        boolean wasUnattributed = !firstSighting && row.getOrderId() == null;
        if (row == null) {
            row = new Dispute();
            row.setStripeDisputeId(stripeDispute.getId());
            row.setOrgId(orgId);
            // Which Stripe mode delivered this chargeback (V130) — stamped once, on the first
            // sighting, so a later lifecycle event cannot re-date it.
            row.setTestMode(!stripeProps.isLiveKey());
            row.setOpenedAt(eventAt != null ? eventAt : Instant.now());
        }
        // Attribution can arrive late (an unreadable charge on the first delivery); never wipe
        // a value we already captured with a null from a thinner payload.
        boolean attachedNow = order != null && wasUnattributed;
        if (order != null) {
            row.setOrderId(order.getId());
            row.setEventId(order.getEventId());
            // Same rule as the attach paths: the order is the precise answer for both the org
            // and whether the money was real; the first sighting could only guess them.
            if (attachedNow) {
                row.setOrgId(order.getOrgId());
                row.setTestMode(order.isTestMode());
            }
        }
        if (charge != null) row.setStripeChargeId(charge.getId());
        if (paymentIntentId != null) row.setStripePaymentIntentId(paymentIntentId);
        row.setAmountMinor(stripeDispute.getAmount() == null ? 0L : stripeDispute.getAmount());
        row.setCurrency(currency(stripeDispute.getCurrency()));
        row.setStatus(status);
        if (status != DisputeStatus.OPEN && row.getClosedAt() == null) {
            row.setClosedAt(eventAt != null ? eventAt : Instant.now());
        }
        if (eventAt != null) row.setLastEventAt(eventAt);
        row = disputes.save(row);

        boolean enteredOpen = status == DisputeStatus.OPEN && previous != DisputeStatus.OPEN;
        boolean fundsBack = status == DisputeStatus.WON || status == DisputeStatus.WITHDRAWN_REINSTATED;

        if (enteredOpen) {
            int revoked = revokeTickets(order, stripeDispute.getId());
            log.warn("[dispute] {} ({}) OPEN org={} event={} order={} amount={} {} — revoked {} ticket(s)",
                    stripeDispute.getId(), eventType, orgId, row.getEventId(), row.getOrderId(),
                    row.getAmountMinor(), row.getCurrency(), revoked);
            publisher.publishEvent(new DisputeOpenedEvent(row.getId()));
        } else if (attachedNow
                && (status == DisputeStatus.OPEN || status == DisputeStatus.LOST)) {
            // The orphan finally found its order. No DisputeOpenedEvent: the first delivery
            // already alerted the organizer, and one chargeback stays one alert.
            int revoked = revokeTickets(order, stripeDispute.getId());
            log.warn("[dispute] {} ({}) {} attributed late to order {} event={} test_mode={} "
                            + "— revoked {} ticket(s)",
                    stripeDispute.getId(), eventType, status.toWire(), row.getOrderId(),
                    row.getEventId(), row.isTestMode(), revoked);
        } else if (fundsBack && previous != status) {
            // Revocation is per ORDER, so only the last WITHHOLDING dispute on it may restore —
            // a LOST sibling keeps the tickets dead, or the sweep would re-revoke them anyway.
            long stillWithholding = order == null ? 0L
                    : disputes.countOtherOpenOrLostByOrderId(order.getId(), row.getId());
            if (stillWithholding > 0L) {
                log.info("[dispute] {} ({}) {} org={} event={} — tickets stay revoked: {} other OPEN "
                                + "or LOST dispute(s) on order {}",
                        stripeDispute.getId(), eventType, status.toWire(), orgId, row.getEventId(),
                        stillWithholding, row.getOrderId());
            } else {
                int restored = restoreTickets(order, stripeDispute.getId());
                log.info("[dispute] {} ({}) {} org={} event={} — restored {} ticket(s), the event's net "
                                + "stops being reduced",
                        stripeDispute.getId(), eventType, status.toWire(), orgId, row.getEventId(), restored);
            }
        } else if (status == DisputeStatus.LOST && previous != DisputeStatus.LOST) {
            // Tickets stay revoked: the cardholder has their money back. The face value comes
            // off the event's payable net; Stripe's separate dispute fee is on the platform.
            log.warn("[dispute] {} ({}) LOST org={} event={} — {} {} comes off the event's payable net",
                    stripeDispute.getId(), eventType, orgId, row.getEventId(),
                    row.getAmountMinor(), row.getCurrency());
        } else {
            // Belt for a dispute attributed before revocation existed — a resent webhook is the
            // only way in. Private helper: a self-call would bypass the transactional proxy.
            int revoked = revokeIfWithholding(row, order);
            if (revoked > 0) {
                log.warn("[dispute] {} ({}) no state change (still {}) — revoked {} still-live ticket(s) "
                                + "on order {}",
                        stripeDispute.getId(), eventType, status.toWire(), revoked, row.getOrderId());
            } else {
                log.info("[dispute] {} ({}) no state change (still {}) — nothing to do",
                        stripeDispute.getId(), eventType, status.toWire());
            }
        }
    }

    /**
     * Attach every orphan dispute recorded against this order's PaymentIntent — the
     * dispute-before-order race, where {@code charge.dispute.created} was delivered before
     * {@code payment_intent.succeeded} and so had no order to revoke.
     *
     * <p>Publishes no {@link DisputeOpenedEvent}: {@link #ingest} already published one on the
     * transition into OPEN, so the organizer gets exactly one alert whichever path attaches.
     *
     * @return how many disputes this call attached.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int attachOrphansForOrder(Order order) {
        if (order == null) return 0;
        String paymentIntentId = order.getStripePaymentIntentId();
        if (paymentIntentId == null || paymentIntentId.isBlank()) return 0;
        int attached = 0;
        for (Dispute row : disputes.findByStripePaymentIntentIdAndOrderIdIsNull(paymentIntentId)) {
            if (attach(row, order)) attached++;
        }
        return attached;
    }

    /**
     * Attach one already-matched orphan — the sweeper's entry point, which found the order by
     * PaymentIntent itself. Same consequences and same silence as {@link #attachOrphansForOrder}.
     *
     * @return {@code true} when THIS call won the conditional update.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean attachOrphan(Dispute row, Order order) {
        if (row == null || order == null) return false;
        String paymentIntentId = order.getStripePaymentIntentId();
        if (paymentIntentId == null || paymentIntentId.isBlank()) return false;
        return attach(row, order);
    }

    /**
     * The conditional UPDATE and its ticket consequence. {@code test_mode} comes from the ORDER
     * rather than the running key: the order recorded whether the money was real, and a sweep
     * under a live key would otherwise re-stamp a test-era orphan as live and withhold real
     * face value from the event's payout net. {@code org_id} likewise: an orphan's org was
     * guessed from the charge's transfer destination and the order is the precise answer.
     */
    private boolean attach(Dispute row, Order order) {
        int updated = disputes.attachToOrder(row.getId(), order.getId(), order.getEventId(),
                order.getOrgId(), order.isTestMode(), Times.nowMicros());
        if (updated == 0) return false;   // another path attached it first — nothing to do

        // OPEN was never revoked (there was no order to revoke), and neither was a dispute
        // already LOST by the time we matched it — that money is gone. WON and
        // WITHDRAWN_REINSTATED revoke nothing.
        boolean revokes = row.getStatus() == DisputeStatus.OPEN || row.getStatus() == DisputeStatus.LOST;
        int revoked = revokes ? revokeTickets(order, row.getStripeDisputeId()) : 0;
        log.warn("[dispute] {} ({}) attached late to order {} event={} test_mode={} — revoked {} ticket(s)",
                row.getStripeDisputeId(), row.getStatus().toWire(), order.getId(), order.getEventId(),
                order.isTestMode(), revoked);
        return true;
    }

    /**
     * Revoke an already-attributed dispute's still-live tickets — the sweep's second pass, for
     * rows no attach path can reach because {@code order_id} was filled in before revocation
     * existed. Idempotent: a converged order has nothing left to change.
     *
     * @return how many tickets THIS call revoked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAttributed(Dispute row, Order order) {
        return revokeIfWithholding(row, order);
    }

    // ── internals ────────────────────────────────────────────────────────────────

    /**
     * Stripe's own status wins; the event type only refines the cases it cannot express.
     * {@code funds_reinstated} fires when a dispute is withdrawn or the bank gives the money
     * back while {@code status} is still an in-progress value. And {@code charge.dispute.closed}
     * means Stripe is done: a close that is neither {@code won} nor {@code lost} (an inquiry
     * closing as {@code warning_closed}) moved no money, so reading it as "still open" would
     * freeze the org's payouts and keep the tickets revoked forever.
     */
    private static DisputeStatus resolveStatus(String stripeStatus, String eventType) {
        DisputeStatus mapped = DisputeStatus.fromStripe(stripeStatus);
        if (mapped != DisputeStatus.OPEN) return mapped;
        if ("charge.dispute.funds_reinstated".equals(eventType)
                || "charge.dispute.closed".equals(eventType)) {
            return DisputeStatus.WITHDRAWN_REINSTATED;
        }
        return mapped;
    }

    /**
     * The withholding guard in front of {@link #revokeTickets}: only OPEN (money at risk) and
     * LOST (money gone) revoke. WON and WITHDRAWN_REINSTATED gave the money back.
     */
    private int revokeIfWithholding(Dispute row, Order order) {
        if (row == null || order == null) return 0;
        if (!DisputeWithholding.STATUSES.contains(row.getStatus())) return 0;
        return revokeTickets(order, row.getStripeDisputeId());
    }

    /**
     * Every ticket on the disputed order that is not already {@code refunded} becomes
     * {@code revoked}. A refunded ticket is left alone — its money already went back by another
     * route and overwriting the state would lose that fact.
     */
    private int revokeTickets(Order order, String disputeId) {
        if (order == null) return 0;
        List<Ticket> all = tickets.findByOrderId(order.getId());
        int changed = 0;
        for (Ticket t : all) {
            if (Ticket.STATE_REFUNDED.equals(t.getState()) || Ticket.STATE_REVOKED.equals(t.getState())) continue;
            t.setState(Ticket.STATE_REVOKED);
            changed++;
        }
        if (changed > 0) {
            tickets.saveAll(all);
            log.info("[dispute] {} revoked {} of {} ticket(s) on order {}",
                    disputeId, changed, all.size(), order.getId());
        }
        return changed;
    }

    /**
     * Reverse of {@link #revokeTickets}: the {@code revoked} tickets on the order go back to the
     * state they had before. A ticket with a {@code redeemed_at} was already scanned at the
     * door, so it returns to {@code redeemed} — restoring it to {@code issued} would hand the
     * same ticket a second entry and let the next scan overwrite {@code redeemed_at}. Scoped to
     * {@code revoked} so a ticket refunded during the dispute keeps its refunded state.
     */
    private int restoreTickets(Order order, String disputeId) {
        if (order == null) return 0;
        List<Ticket> all = tickets.findByOrderId(order.getId());
        int changed = 0;
        for (Ticket t : all) {
            if (!Ticket.STATE_REVOKED.equals(t.getState())) continue;
            t.setState(t.getRedeemedAt() != null ? Ticket.STATE_REDEEMED : Ticket.STATE_ISSUED);
            changed++;
        }
        if (changed > 0) {
            tickets.saveAll(all);
            log.info("[dispute] {} restored {} of {} ticket(s) on order {}",
                    disputeId, changed, all.size(), order.getId());
        }
        return changed;
    }

    /**
     * Org for the dispute row ({@code org_id} is NOT NULL). The order is the precise answer;
     * without one we fall back to the connected account the charge paid out to, then to the
     * event envelope's account — a dispute we cannot attribute is worse than useless, because
     * an unattributed open dispute would silently fail to freeze anyone's payouts.
     */
    private UUID resolveOrgId(Order order, Charge charge, String connectedAccount) {
        if (order != null) return order.getOrgId();
        String destination = (charge == null || charge.getTransferData() == null)
                ? null : charge.getTransferData().getDestination();
        String acctId = firstNonBlank(destination, connectedAccount);
        if (acctId == null) return null;
        return orgs.findByStripeAccountId(acctId).map(Organization::getId).orElse(null);
    }

    /** True when Stripe handed us an event older than the one that last wrote this row. */
    private boolean isStale(Dispute row, Instant eventAt, String disputeId, String eventType) {
        if (eventAt == null || row.getLastEventAt() == null) return false;
        if (!eventAt.isBefore(row.getLastEventAt())) return false;
        log.info("[dispute] {} ({}) ignored — event created {} predates the row's last write {} "
                + "(out-of-order delivery)", disputeId, eventType, eventAt, row.getLastEventAt());
        return true;
    }

    private static String currency(String c) {
        return c == null ? "eur" : c.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
