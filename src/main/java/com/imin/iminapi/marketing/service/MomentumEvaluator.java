package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.service.AllCampaignsConsent;
import com.imin.iminapi.audienceplan.service.MomentumPlanTarget;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.model.MomentumTriggerType;
import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.event.EventSalesTotals;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Hourly Momentum evaluator (spec §6.1). Walks published, future, on-sale events,
 * computes sales-curve metrics, applies the four trigger rules with guardrails
 * (min-audience floor, 7-day cooldown, one live suggestion per trigger, expiry),
 * and persists a pre-drafted suggestion. Then, on every pass, it considers
 * {@code clicked_no_buy}: recent ticket-link clickers with no live ticket. Suggestions only — never sends.
 */
@Component
public class MomentumEvaluator {

    private static final Logger log = LoggerFactory.getLogger(MomentumEvaluator.class);

    /** How far back clicked_no_buy reads ticket-link clicks; a constant, so the webapp's "last 7 days" stays true. */
    static final Duration CLICKED_NO_BUY_WINDOW = Duration.ofDays(7);

    private final EventRepository events;
    private final OrderRepository orders;
    private final TicketTierRepository tiers;
    private final TicketRepository tickets;
    private final EventSalesTotals salesTotals;
    private final MomentumSuggestionRepository suggestions;
    private final MomentumThresholds thresholds;
    private final MomentumCopyGenerator copy;
    private final SendGateService sendGate;
    private final SegmentService segments;
    private final MomentumNotifier notifier;
    private final MomentumPlanTarget planTarget;
    private final ApplicationEventPublisher publisher;
    private final AllCampaignsConsent allCampaignsConsent;
    private final CampaignRecipientRepository recipients;
    private final MembershipRepository memberships;

    public MomentumEvaluator(EventRepository events, OrderRepository orders,
                             TicketTierRepository tiers, TicketRepository tickets,
                             EventSalesTotals salesTotals, MomentumSuggestionRepository suggestions,
                             MomentumThresholds thresholds, MomentumCopyGenerator copy,
                             SendGateService sendGate, SegmentService segments,
                             MomentumNotifier notifier, MomentumPlanTarget planTarget,
                             ApplicationEventPublisher publisher,
                             AllCampaignsConsent allCampaignsConsent,
                             CampaignRecipientRepository recipients, MembershipRepository memberships) {
        this.events = events;
        this.orders = orders;
        this.tiers = tiers;
        this.tickets = tickets;
        this.salesTotals = salesTotals;
        this.suggestions = suggestions;
        this.thresholds = thresholds;
        this.copy = copy;
        this.sendGate = sendGate;
        this.segments = segments;
        this.notifier = notifier;
        this.planTarget = planTarget;
        this.publisher = publisher;
        this.allCampaignsConsent = allCampaignsConsent;
        this.recipients = recipients;
        this.memberships = memberships;
    }

    @Scheduled(cron = "0 0 * * * *") // top of every hour
    @SchedulerLock(name = "momentum_evaluator", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        runOnce();
    }

    /**
     * Plain, non-scheduled evaluation body so tests drive one pass deterministically —
     * calling this directly bypasses the {@code @SchedulerLock} proxy (whose lock
     * transaction would otherwise wrap and roll back the per-suggestion saves). Same
     * split as {@code MetaCapiPoller.scheduledDrain() -> drain()} and
     * {@code CampaignDispatcher.run() -> runOnce()}. No method-level {@code @Transactional}:
     * each suggestion / expiry write is its own repository-managed transaction, so one
     * failing event never rolls back another's persisted suggestion.
     */
    public void runOnce() {
        Instant now = Instant.now();
        expireStale(now);

        List<Event> candidates = events.findMomentumCandidates(now);
        log.info("MomentumEvaluator: {} on-sale events to evaluate", candidates.size());

        for (Event e : candidates) {
            try {
                evaluateOne(e, now);
            } catch (Exception ex) {
                log.error("Momentum evaluation failed for event {}: {}", e.getId(), ex.getMessage());
            }
        }
    }

    private int sendableCount(UUID orgId, List<UUID> memberIds) {
        return allCampaignsConsent.mailable(orgId, sendGate.evaluate(orgId, memberIds).sendable()).size();
    }

    void evaluateOne(Event e, Instant now) {
        // sold = live TICKETS sold, the figure the events list and Overview show — NOT an order
        // count (one order can hold several tickets). Velocity and spark are live orders too.
        int sold = salesTotals.soldForEvent(e.getId());
        int capacity = tiers.sumQuantityByEventId(e.getId());
        List<Object[]> last7d = orders.findLiveCreatedAtAndTotalSince(
                e.getId(), now.minus(Duration.ofDays(7)));
        // Real per-day sold series for the card's chart: live SOLD tickets from the tickets
        // table, bucketed by UTC day in MomentumMetrics. Snapshotted with the rest of the
        // evidence so the curve the organizer sees is the curve the engine fired on — and
        // so it can never contradict the `sold` scalar printed next to it.
        List<Instant> soldAt = tickets.findSoldCreatedAtSince(
                e.getId(), now.minus(Duration.ofDays(MomentumMetrics.SPARK_DAYS)));
        // Seats, not live sales, decide sold out and "N left": a test-era ticket still holds its seat.
        int seatsLeft = Math.max(0, capacity - tiers.sumSoldByEventId(e.getId()));
        MomentumMetrics m = MomentumMetrics.compute(
                sold, capacity, last7d.size(), e.getOnSaleAt(), e.getStartsAt(), now, soldAt, seatsLeft);

        MomentumTriggerType fired = pickTrigger(m);
        log.info("Momentum eval event={} sold={} cap={} sellThrough={} daysOut={} hoursToStart={} ticketsPerDay7d={} required50={} -> {}",
                e.getId(), m.sold(), m.capacity(), m.sellThroughPct(), m.daysOut(), m.hoursToStart(),
                String.format(java.util.Locale.ROOT, "%.2f", m.ticketsPerDay7d()),
                String.format(java.util.Locale.ROOT, "%.2f", m.requiredVelocityToTarget(thresholds.getSlumpTargetPct())),
                fired == null ? "no-trigger" : fired.wireValue());

        if (fired != null) fireSalesCurveTrigger(e, m, fired, now);
        // A sales-curve suggestion stored just above is live, so clicked_no_buy stands down this pass.
        considerClickedNoBuy(e, m, now);
    }

    private void fireSalesCurveTrigger(Event e, MomentumMetrics m, MomentumTriggerType fired, Instant now) {
        // Guardrail: single live suggestion per (event, trigger).
        if (suggestions.findByEventIdAndTriggerTypeAndStatus(
                e.getId(), fired.wireValue(), "suggested").isPresent()) {
            return;
        }
        if (inCooldown(e, fired, now)) return;

        publishTriggered(e, fired);

        // Target: the plan's best segment (holdouts removed) when it clears the floor, else the prebuilt Repeat.
        MomentumPlanTarget.Target planned = planTarget(e);
        if (planned != null) {
            int plannedSendable = sendableCount(e.getOrgId(), planned.membershipIds());
            if (plannedSendable < thresholds.getMinAudienceFloor()) {
                log.info("Momentum: event {} plan target {}/{} below floor ({} < {}), using Repeat",
                        e.getId(), planned.classKey(), planned.genreFit(), plannedSendable,
                        thresholds.getMinAudienceFloor());
                planned = null;
            }
        }

        UUID segmentId = null;
        if (planned != null) {
            try {
                segmentId = planTarget.snapshot(e.getOrgId(), e.getName(), planned);
            } catch (RuntimeException ex) {
                log.warn("Momentum: plan snapshot for event {} failed, using Repeat: {}", e.getId(), ex.getMessage());
                planned = null;
            }
        }
        if (planned == null) {
            segmentId = segments.defaultTargetSegmentId(e.getOrgId());
            List<UUID> memberIds = segments.resolveMembershipIds(e.getOrgId(), segmentId);

            // Guardrail: min-audience floor via SendGate (spec §6.1), and ConsentGate while it applies to every campaign.
            int sendable = sendableCount(e.getOrgId(), memberIds);
            if (sendable < thresholds.getMinAudienceFloor()) {
                log.info("Momentum: event {} trigger {} skipped — audience {} < floor {}",
                        e.getId(), fired.wireValue(), sendable, thresholds.getMinAudienceFloor());
                return;
            }
        }

        String why;
        try {
            why = persistSuggestion(e, fired, m, segmentId, now, null);
        } catch (RuntimeException ex) {
            if (planned != null) {
                try {
                    planTarget.discard(e.getOrgId(), segmentId);
                } catch (RuntimeException cleanup) {
                    ex.addSuppressed(cleanup);
                }
            }
            throw ex;
        }
        // Best-effort in-app ping in its OWN (REQUIRES_NEW) transaction — a failure here
        // cannot roll back the suggestion just persisted (see MomentumNotifier).
        notifier.notifyOwner(e.getOrgId(), fired.wireValue(), why);
    }

    /**
     * Members who clicked this event's ticket link in its email campaigns within {@link #CLICKED_NO_BUY_WINDOW},
     * minus live-ticket holders, frozen into a Momentum segment. Objected and erase-pending members never qualify.
     */
    private void considerClickedNoBuy(Event e, MomentumMetrics m, Instant now) {
        MomentumTriggerType trigger = MomentumTriggerType.CLICKED_NO_BUY;
        if (m.capacity() > 0 && m.seatsLeft() <= 0) return;
        // Any live suggestion for the event, whatever its trigger, holds this one back so they never stack.
        if (suggestions.existsByEventIdAndStatus(e.getId(), "suggested")) return;
        if (inCooldown(e, trigger, now)) return;

        List<UUID> clickers = recipients.findTicketClickersForEvent(
                e.getOrgId(), e.getId(), now.minus(CLICKED_NO_BUY_WINDOW));
        if (clickers.isEmpty()) return;
        Set<UUID> holders = new HashSet<>();
        for (Object[] pair : memberships.findAttendedEventPairs(e.getOrgId(), List.of(e.getId()))) {
            holders.add((UUID) pair[0]);
        }
        List<UUID> targets = clickers.stream().filter(id -> !holders.contains(id)).toList();
        if (targets.isEmpty()) return;

        int sendable = sendableCount(e.getOrgId(), targets);
        if (sendable < thresholds.getMinAudienceFloor()) {
            log.info("Momentum: event {} trigger {} skipped — audience {} < floor {}",
                    e.getId(), trigger.wireValue(), sendable, thresholds.getMinAudienceFloor());
            return;
        }

        publishTriggered(e, trigger);
        UUID segmentId = planTarget.snapshot(e.getOrgId(), clickedNoBuySegmentName(e.getName()), targets);
        String why;
        try {
            why = persistSuggestion(e, trigger, m, segmentId, now, targets.size());
        } catch (RuntimeException ex) {
            try {
                planTarget.discard(e.getOrgId(), segmentId);
            } catch (RuntimeException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
        notifier.notifyOwner(e.getOrgId(), trigger.wireValue(), why);
    }

    private static String clickedNoBuySegmentName(String eventName) {
        String base = "Momentum: clicked tickets, no ticket";
        return eventName == null || eventName.isBlank() ? base : base + " · " + eventName.trim();
    }

    /** Guardrail: cooldown per (event, trigger), counted from the last suggestion in any status. */
    private boolean inCooldown(Event e, MomentumTriggerType trigger, Instant now) {
        Optional<MomentumSuggestion> last = suggestions
                .findTopByEventIdAndTriggerTypeOrderBySuggestedAtDesc(e.getId(), trigger.wireValue());
        return last.isPresent() && last.get().getSuggestedAt()
                .isAfter(now.minus(Duration.ofDays(thresholds.getCooldownDays())));
    }

    /** The event's audience plan is refreshed off-thread; the draft still uses the plan stored now. */
    private void publishTriggered(Event e, MomentumTriggerType trigger) {
        try {
            publisher.publishEvent(new MomentumTriggered(e.getOrgId(), e.getId(), trigger.wireValue()));
        } catch (RuntimeException ex) {
            log.warn("Momentum: plan refresh for event {} not queued: {}", e.getId(), ex.getMessage());
        }
    }

    /** Null when the plan gives no usable target; a plan failure never stops the Repeat fallback. */
    private MomentumPlanTarget.Target planTarget(Event e) {
        try {
            return planTarget.best(e).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Momentum: plan target for event {} unavailable, using Repeat: {}", e.getId(), ex.getMessage());
            return null;
        }
    }

    /** Writes the suggestion and returns its "why" line; {@code clickedNoTicket} is null for sales-curve triggers. */
    private String persistSuggestion(Event e, MomentumTriggerType fired, MomentumMetrics m, UUID segmentId,
                                   Instant now, Integer clickedNoTicket) {
        MomentumDraftPayload draft = copy.generate(
                fired,
                e.getName(),
                e.getStartsAt() == null ? null : e.getStartsAt().toString(),
                e.getVenueName(),
                triggerContext(fired, m),
                e.getPosterUrl(),
                segmentId);

        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(e.getOrgId());
        s.setEventId(e.getId());
        s.setTriggerType(fired.wireValue());
        s.setStatus("suggested");
        s.setMetricsSnapshot(m.toJson(clickedNoTicket));
        s.setDraftPayload(toDraftJson(draft));
        s.setSuggestedAt(now);
        suggestions.save(s);
        return draft.why();
    }

    /** First matching rule, evaluated most-urgent first (spec §6.1). */
    private MomentumTriggerType pickTrigger(MomentumMetrics m) {
        if (m.capacity() > 0 && m.seatsLeft() <= 0) {
            return MomentumTriggerType.SOLD_OUT;
        }
        // Urgency: within 72h of start AND sell-through in [30,90].
        // Uses the true hours-to-doors, not daysOut*24 — the coarse form fired the
        // 72h window for anything up to ~95h out (daysOut=3 -> 72), because daysOut
        // is floor(hours/24).
        long hoursToStart = m.hoursToStart();
        if (hoursToStart <= thresholds.getUrgencyBeforeHours()
                && m.sellThroughPct() >= thresholds.getUrgencyMinSellThroughPct()
                && m.sellThroughPct() <= thresholds.getUrgencyMaxSellThroughPct()) {
            return MomentumTriggerType.URGENCY_72H;
        }
        // Launch push: >=48h since on-sale AND >=1 order AND sell-through < 15%.
        if (m.hoursSinceOnSale() >= thresholds.getLaunchAfterHours()
                && m.sold() >= 1
                && m.sellThroughPct() < thresholds.getLaunchMaxSellThroughPct()) {
            return MomentumTriggerType.LAUNCH_PUSH;
        }
        // Slump: >=14 days out AND >=15% sold AND recent pace below required-to-50%.
        // Both sides are TICKETS/day. Previously the left side was velocity7d
        // (ORDERS/day = ordersLast7d/7) compared to a tickets/day threshold — since
        // an order is >=1 ticket, orders/day understates the real pace, so slump
        // fired more readily than it should. ticketsPerDay7d is the real tickets/day
        // over the same recent-7-day window (from the sold-ticket spark).
        if (m.daysOut() >= thresholds.getSlumpMinDaysOut()
                && m.sellThroughPct() >= thresholds.getSlumpMinSellThroughPct()
                && m.ticketsPerDay7d() < m.requiredVelocityToTarget(thresholds.getSlumpTargetPct())) {
            return MomentumTriggerType.SLUMP;
        }
        return null;
    }

    private String triggerContext(MomentumTriggerType t, MomentumMetrics m) {
        return switch (t) {
            case SOLD_OUT -> "Sold out — " + m.seatsGone() + " of " + m.capacity() + " gone.";
            case URGENCY_72H -> m.daysOut() + " days left, " + m.remaining() + " tickets remain.";
            case LAUNCH_PUSH -> "On sale " + m.hoursSinceOnSale() + "h, only "
                    + m.sellThroughPct() + "% sold.";
            case SLUMP -> "Pace has slowed at " + m.sellThroughPct() + "% sold, "
                    + m.daysOut() + " days out.";
            // The organizer may know why these people were chosen; the email itself must not reveal it.
            case CLICKED_NO_BUY -> "The recipients clicked through to this event's tickets from an earlier email in the last "
                    + CLICKED_NO_BUY_WINDOW.toDays() + " days and hold no ticket"
                    + (m.capacity() > 0 ? "; " + m.remaining() + " tickets remain" : "")
                    + ". In the subject, preheader and body, never say or imply that the reader clicked a link,"
                    + " opened an email or was tracked.";
        };
    }

    private String toDraftJson(MomentumDraftPayload d) {
        return "{"
                + "\"subject\":" + jsonStr(d.subject()) + ","
                + "\"preheader\":" + jsonStr(d.preheader()) + ","
                + "\"bodyMd\":" + jsonStr(d.bodyMd()) + ","
                + "\"segmentId\":" + jsonStr(d.segmentId()) + ","
                + "\"posterUrl\":" + jsonStr(d.posterUrl()) + ","
                + "\"why\":" + jsonStr(d.why())
                + "}";
    }

    private String jsonStr(String v) {
        if (v == null) return "null";
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    /** Expire live 'suggested' rows whose event has started (spec §6.1 expiry). */
    private void expireStale(Instant now) {
        for (MomentumSuggestion s : suggestions.findByStatus("suggested")) {
            events.findActive(s.getEventId()).ifPresentOrElse(ev -> {
                if (ev.getStartsAt() != null && !ev.getStartsAt().isAfter(now)) {
                    s.setStatus("expired");
                    s.setActedAt(now);
                    suggestions.save(s);
                }
            }, () -> {
                s.setStatus("expired");
                s.setActedAt(now);
                suggestions.save(s);
            });
        }
    }
}
