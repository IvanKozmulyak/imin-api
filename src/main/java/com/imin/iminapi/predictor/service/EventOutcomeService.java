package com.imin.iminapi.predictor.service;

import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.predictor.model.AttendanceSource;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes the event outcome record (spec §6.1) — the predictor's data foundation.
 *
 * <p>Two write passes plus a retro-reconstruction:
 * <ul>
 *   <li>{@link #freezeOnPublish(Event)} — called INSIDE {@code EventService.publish()}'s
 *       transaction the moment an event goes LIVE. Snapshots the pre-event attributes the
 *       predictor could still influence. Idempotent: a re-publish (after an unpublish)
 *       overwrites the frozen fields and preserves any finalized fields.</li>
 *   <li>{@link #finalize(EventOutcome, Event, Instant)} — the post-event pass, run by
 *       {@link EventOutcomeFinalizeJob} once the event has ended + a settlement grace.</li>
 *   <li>{@link #reconstructIfAbsent(Event)} — the one-shot retro-backfill for events that
 *       were already published before this table existed; flags {@code snapshotReconstructed}.</li>
 * </ul>
 *
 * <p>Honesty (spec no-fabrication rule): venue type, indoor/open-air, AI provenance and NPS
 * are recorded NULL because the data model cannot supply them; attendance records its own
 * source ('scans' vs 'sales') instead of pretending scan coverage is complete.
 */
@Service
public class EventOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(EventOutcomeService.class);

    private final EventOutcomeRepository outcomes;
    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final TicketTierRepository tiers;
    private final PromoCodeRepository promos;
    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final FunnelEventRepository funnel;
    private final CampaignRecipientRepository campaignRecipients;

    public EventOutcomeService(EventOutcomeRepository outcomes, EventRepository events,
                               OrganizationRepository orgs, TicketTierRepository tiers,
                               PromoCodeRepository promos, TicketRepository tickets,
                               OrderRepository orders, FunnelEventRepository funnel,
                               CampaignRecipientRepository campaignRecipients) {
        this.outcomes = outcomes;
        this.events = events;
        this.orgs = orgs;
        this.tiers = tiers;
        this.promos = promos;
        this.tickets = tickets;
        this.orders = orders;
        this.funnel = funnel;
        this.campaignRecipients = campaignRecipients;
    }

    // ---- JSON snapshot shapes -------------------------------------------------

    /** Frozen tier structure element. */
    public record TierSnapshot(String name, int priceMinor, int quantity,
                               Instant saleStartsAt, Instant saleClosesAt) {}

    /** Frozen promo config element (summary — no usage counters). */
    public record PromoSnapshot(String code, int discountPct, int maxUses) {}

    /** Finalized per-tier sold count. */
    public record SoldTier(UUID tierId, String tierName, long sold) {}

    // ---- Freeze at publish ----------------------------------------------------

    /**
     * Snapshot the pre-event fields at publish. Joins the caller's transaction
     * ({@code EventService.publish()}). Idempotent upsert keyed by event id.
     */
    @Transactional
    public void freezeOnPublish(Event e) {
        EventOutcome o = outcomes.findById(e.getId()).orElseGet(() -> {
            EventOutcome n = new EventOutcome();
            n.setEventId(e.getId());
            return n;
        });
        applyFrozenFields(o, e, false);
        outcomes.save(o);
    }

    /**
     * Reconstruct a frozen snapshot for an already-published event that predates this table.
     * No-op when a row already exists (so it never clobbers a real publish-time freeze).
     * The reconstructed fields come from the CURRENT event row, so they are best-effort —
     * flagged {@code snapshotReconstructed=true} so the corpus can weigh them accordingly.
     */
    @Transactional
    public boolean reconstructIfAbsent(Event e) {
        if (outcomes.existsById(e.getId())) return false;
        EventOutcome o = new EventOutcome();
        o.setEventId(e.getId());
        applyFrozenFields(o, e, true);
        outcomes.save(o);
        return true;
    }

    private void applyFrozenFields(EventOutcome o, Event e, boolean reconstructed) {
        o.setOrgId(e.getOrgId());
        o.setCity(blankToNull(e.getVenueCity()));
        o.setCountry(blankToNull(e.getVenueCountry()));
        o.setGenreFamily(blankToNull(e.getGenre()));
        // venueType / indoorOpenAir stay NULL — no source in the data model.

        int capacity = tiers.sumQuantityByEventId(e.getId());
        o.setCapacity(capacity);
        o.setCapacityBand(CapacityBand.of(capacity));

        ZoneId zone = resolveZone(e.getTimezone());
        Instant startsAt = e.getStartsAt();
        o.setEventDate(startsAt);
        o.setSeason(Season.of(startsAt, zone));
        o.setDayOfWeek(startsAt == null ? null
                : (short) startsAt.atZone(zone).getDayOfWeek().getValue());

        Instant publishedAt = e.getPublishedAt();
        o.setLeadTimeDays(wholeDaysBetween(publishedAt, startsAt));

        o.setTierStructureJson(writeJson(tierSnapshots(e.getId()), "[]"));
        o.setPromoConfigJson(writeJson(promoSnapshots(e.getId()), "[]"));

        // AI provenance (V71): pass through the events-row stamps. Tri-state — TRUE (verified
        // sourceConceptId at create / never for poster), FALSE (manual poster upload), NULL
        // (unknown; today's FE promote flow sends no signal). Never invented here.
        o.setConceptAiGenerated(e.getConceptAiGenerated());
        o.setPosterAiGenerated(e.getPosterAiGenerated());

        orgs.findById(e.getOrgId()).ifPresent(org ->
                o.setOrganizerTenureDays(organizerTenureDays(org, publishedAt)));
        o.setPriorEventCount(priorPublishedCount(e.getOrgId()));

        o.setSnapshotReconstructed(reconstructed);
        o.setFrozenAt(Times.nowMicros());
    }

    private List<TierSnapshot> tierSnapshots(UUID eventId) {
        List<TierSnapshot> out = new ArrayList<>();
        tiers.findByEventIdOrderBySortOrderAsc(eventId).forEach(t ->
                out.add(new TierSnapshot(t.getName(), t.getPriceMinor(), t.getQuantity(),
                        t.getSaleStartsAt(), t.getSaleClosesAt())));
        return out;
    }

    private List<PromoSnapshot> promoSnapshots(UUID eventId) {
        List<PromoSnapshot> out = new ArrayList<>();
        promos.findByEventId(eventId).forEach(p ->
                out.add(new PromoSnapshot(p.getCode(), p.getDiscountPct(), p.getMaxUses())));
        return out;
    }

    private Integer organizerTenureDays(Organization org, Instant publishedAt) {
        Instant created = org.getCreatedAt();
        Instant ref = publishedAt != null ? publishedAt : Times.nowMicros();
        if (created == null) return null;
        return (int) Math.max(0, ChronoUnit.DAYS.between(created, ref));
    }

    /** Prior published events for the org, excluding the one being frozen (which is already LIVE). */
    private int priorPublishedCount(UUID orgId) {
        return (int) Math.max(0, events.countPublished(orgId) - 1);
    }

    // ---- Finalize post-event --------------------------------------------------

    /**
     * Fill the post-event fields. Assumes the caller has confirmed the event is due
     * (ended + grace). Idempotent — recomputes from source every time, so a re-run
     * simply rewrites the same figures (and picks up any late refunds if re-run).
     */
    @Transactional
    public void finalize(EventOutcome o, Event e, Instant now) {
        long soldTotal = 0;
        long grossRevenue = 0;
        long redeemed = 0;
        List<SoldTier> perTier = new ArrayList<>();
        for (Object[] row : tickets.tierAggregates(e.getId())) {
            UUID tierId = (UUID) row[0];
            String tierName = (String) row[1];
            long sold = ((Number) row[2]).longValue();
            long gross = ((Number) row[3]).longValue();
            long red = ((Number) row[4]).longValue();
            soldTotal += sold;
            grossRevenue += gross;
            redeemed += red;
            perTier.add(new SoldTier(tierId, tierName, sold));
        }

        o.setSoldTotal((int) soldTotal);
        o.setSoldPerTierJson(writeJson(perTier, "[]"));
        o.setGrossRevenueMinor(grossRevenue);

        Integer capacity = o.getCapacity();
        boolean sellOut = capacity != null && capacity > 0 && soldTotal >= capacity;
        o.setSellOut(sellOut);
        if (sellOut) {
            Instant lastSold = tickets.findLastSoldCreatedAt(e.getId());
            o.setTimeToSellOutHours(wholeHoursBetween(e.getPublishedAt(), lastSold));
        } else {
            o.setTimeToSellOutHours(null);
        }

        long refundCount = tickets.countByEventIdAndState(e.getId(), "refunded");
        o.setRefundCount((int) refundCount);
        long issued = soldTotal + refundCount;
        o.setRefundRate(issued == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(refundCount).divide(BigDecimal.valueOf(issued), 4, RoundingMode.HALF_UP));

        // Attendance: door-scan truth when ANY scan exists; else the sales fallback (recorded, not hidden).
        if (redeemed > 0) {
            o.setAttendance((int) redeemed);
            o.setAttendanceSource(AttendanceSource.SCANS);
        } else {
            o.setAttendance((int) soldTotal);
            o.setAttendanceSource(AttendanceSource.SALES);
        }

        int views = 0, checkoutStarts = 0;
        for (Object[] row : funnel.countDistinctAnonByStage(e.getId())) {
            String stage = (String) row[0];
            int count = ((Number) row[1]).intValue();
            if (FunnelEvent.STAGE_PAGE_VIEW.equals(stage)) views = count;
            else if (FunnelEvent.STAGE_CHECKOUT_START.equals(stage)) checkoutStarts = count;
        }
        o.setFunnelViews(views);
        o.setFunnelCheckoutStarts(checkoutStarts);
        o.setFunnelPaid((int) orders.countByEventId(e.getId()));

        Instant windowFrom = e.getPublishedAt() != null ? e.getPublishedAt() : e.getCreatedAt();
        Instant windowTo = e.getEndsAt() != null ? e.getEndsAt() : now;
        o.setCampaignSends((int) campaignRecipients.countDispatchedForEventInWindow(
                e.getId(), windowFrom, windowTo));

        o.setNps(null); // survey does not exist yet
        o.setFinalizedAt(now);
        outcomes.save(o);
    }

    // ---- helpers --------------------------------------------------------------

    private String writeJson(Object value, String fallback) {
        try {
            return PredictorJson.MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("EventOutcomeService: JSON serialization failed, using fallback", ex);
            return fallback;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static Integer wholeDaysBetween(Instant from, Instant to) {
        if (from == null || to == null) return null;
        return (int) ChronoUnit.DAYS.between(from, to);
    }

    private static Integer wholeHoursBetween(Instant from, Instant to) {
        if (from == null || to == null) return null;
        return (int) Math.max(0, Duration.between(from, to).toHours());
    }

    private static ZoneId resolveZone(String raw) {
        if (raw == null || raw.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException ex) {
            return ZoneId.of("UTC");
        }
    }
}
