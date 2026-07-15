package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.model.MomentumTriggerType;
import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Hourly Momentum evaluator (spec §6.1). Walks published, future, on-sale events,
 * computes sales-curve metrics, applies the four trigger rules with guardrails
 * (min-audience floor, 7-day cooldown, one live suggestion per trigger, expiry),
 * and persists a pre-drafted suggestion. Suggestions only — never sends.
 */
@Component
public class MomentumEvaluator {

    private static final Logger log = LoggerFactory.getLogger(MomentumEvaluator.class);

    private final EventRepository events;
    private final OrderRepository orders;
    private final TicketTierRepository tiers;
    private final MomentumSuggestionRepository suggestions;
    private final MomentumThresholds thresholds;
    private final MomentumCopyGenerator copy;
    private final SendGateService sendGate;
    private final SegmentService segments;
    private final MomentumNotifier notifier;

    public MomentumEvaluator(EventRepository events, OrderRepository orders,
                             TicketTierRepository tiers, MomentumSuggestionRepository suggestions,
                             MomentumThresholds thresholds, MomentumCopyGenerator copy,
                             SendGateService sendGate, SegmentService segments,
                             MomentumNotifier notifier) {
        this.events = events;
        this.orders = orders;
        this.tiers = tiers;
        this.suggestions = suggestions;
        this.thresholds = thresholds;
        this.copy = copy;
        this.sendGate = sendGate;
        this.segments = segments;
        this.notifier = notifier;
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

    private void evaluateOne(Event e, Instant now) {
        // sold = TICKETS sold (SUM of tier.sold), the same figure SalesDashboardService
        // reports — NOT orders.countByEventId (one order can hold several tickets).
        int sold = tiers.sumSoldByEventId(e.getId());
        int capacity = tiers.sumQuantityByEventId(e.getId());
        List<Object[]> last7d = orders.findCreatedAtAndTotalSince(
                e.getId(), now.minus(Duration.ofDays(7)));
        MomentumMetrics m = MomentumMetrics.compute(
                sold, capacity, last7d.size(), e.getOnSaleAt(), e.getStartsAt(), now);

        MomentumTriggerType fired = pickTrigger(m);
        log.info("Momentum eval event={} sold={} cap={} sellThrough={} daysOut={} vel7d={} required50={} -> {}",
                e.getId(), m.sold(), m.capacity(), m.sellThroughPct(), m.daysOut(),
                String.format(java.util.Locale.ROOT, "%.2f", m.velocity7d()),
                String.format(java.util.Locale.ROOT, "%.2f", m.requiredVelocityToTarget(thresholds.getSlumpTargetPct())),
                fired == null ? "no-trigger" : fired.wireValue());

        if (fired == null) return;

        // Guardrail: single live suggestion per (event, trigger).
        if (suggestions.findByEventIdAndTriggerTypeAndStatus(
                e.getId(), fired.wireValue(), "suggested").isPresent()) {
            return;
        }
        // Guardrail: 7-day cooldown per (event, trigger).
        Optional<MomentumSuggestion> last = suggestions
                .findTopByEventIdAndTriggerTypeOrderBySuggestedAtDesc(e.getId(), fired.wireValue());
        if (last.isPresent() && last.get().getSuggestedAt()
                .isAfter(now.minus(Duration.ofDays(thresholds.getCooldownDays())))) {
            return;
        }

        // Resolve target segment (v1: the org's prebuilt "Repeat" segment) -> membership ids.
        UUID segmentId = segments.defaultTargetSegmentId(e.getOrgId());
        List<UUID> memberIds = segments.resolveMembershipIds(e.getOrgId(), segmentId);

        // Guardrail: min-audience floor via SendGate (spec §6.1).
        int sendable = sendGate.evaluate(e.getOrgId(), memberIds).sendable().size();
        if (sendable < thresholds.getMinAudienceFloor()) {
            log.info("Momentum: event {} trigger {} skipped — audience {} < floor {}",
                    e.getId(), fired.wireValue(), sendable, thresholds.getMinAudienceFloor());
            return;
        }

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
        s.setMetricsSnapshot(m.toJson());
        s.setDraftPayload(toDraftJson(draft));
        s.setSuggestedAt(now);
        suggestions.save(s);
        // Best-effort in-app ping in its OWN (REQUIRES_NEW) transaction — a failure here
        // cannot roll back the suggestion just persisted (see MomentumNotifier).
        notifier.notifyOwner(e.getOrgId(), fired.wireValue(), draft.why());
    }

    /** First matching rule, evaluated most-urgent first (spec §6.1). */
    private MomentumTriggerType pickTrigger(MomentumMetrics m) {
        if (m.sellThroughPct() >= 100) {
            return MomentumTriggerType.SOLD_OUT;
        }
        // Urgency: within 72h of start AND sell-through in [30,90].
        long hoursToStart = m.daysOut() * 24L; // coarse; daysOut is floor of hours/24
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
        // Slump: >=14 days out AND >=15% sold AND velocity below required-to-50%.
        if (m.daysOut() >= thresholds.getSlumpMinDaysOut()
                && m.sellThroughPct() >= thresholds.getSlumpMinSellThroughPct()
                && m.velocity7d() < m.requiredVelocityToTarget(thresholds.getSlumpTargetPct())) {
            return MomentumTriggerType.SLUMP;
        }
        return null;
    }

    private String triggerContext(MomentumTriggerType t, MomentumMetrics m) {
        int remaining = Math.max(0, m.capacity() - m.sold());
        return switch (t) {
            case SOLD_OUT -> "Sold out — " + m.sold() + " of " + m.capacity() + " gone.";
            case URGENCY_72H -> m.daysOut() + " days left, " + remaining + " tickets remain.";
            case LAUNCH_PUSH -> "On sale " + m.hoursSinceOnSale() + "h, only "
                    + m.sellThroughPct() + "% sold.";
            case SLUMP -> "Pace has slowed at " + m.sellThroughPct() + "% sold, "
                    + m.daysOut() + " days out.";
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
