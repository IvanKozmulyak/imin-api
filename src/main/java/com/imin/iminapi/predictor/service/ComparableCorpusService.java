package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.RelaxationLevel;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Comparable corpus retrieval (spec §6.4) — selects the events an estimate leans on and
 * enforces privacy AT THE QUERY LEVEL.
 *
 * <p>Segment = city × genre family × capacity band × season. When the cluster is sparse
 * (&lt; {@link #MIN_CLUSTER}) it relaxes down the fixed ladder ({@link RelaxationLevel}):
 * city → country, exact genre → family (identity today — outcomes store only the family),
 * season dropped last. The applied relaxation is returned so the surface can be honest about
 * how far the net was cast.
 *
 * <p><b>Privacy (never-cuttable, gate item 5).</b> Foreign events (a different org) appear
 * ONLY as aggregates over a cluster of ≥ {@link #MIN_CLUSTER}, with attendance rounded to
 * {@link #ATTENDANCE_ROUNDING} and revenue to {@link #REVENUE_ROUNDING_MINOR} — never named,
 * never individually plottable. The requesting org's own events are exempt and returned by
 * name. These three numbers are compile-time constants, NOT config, so they can never be
 * misconfigured below the legal floor.
 */
@Service
public class ComparableCorpusService {

    /** Minimum cluster size — the ≥5 privacy floor (spec §6.4, gate item 5). NEVER config. */
    public static final int MIN_CLUSTER = 5;
    /** Foreign attendance is rounded to the nearest this-many attendees. NEVER config. */
    public static final int ATTENDANCE_ROUNDING = 10;
    /** Foreign revenue is rounded to the nearest €100 (in minor units). NEVER config. */
    public static final long REVENUE_ROUNDING_MINOR = 10_000L;

    private final EventOutcomeRepository outcomes;
    private final EventRepository events;

    public ComparableCorpusService(EventOutcomeRepository outcomes, EventRepository events) {
        this.outcomes = outcomes;
        this.events = events;
    }

    /** One of the requesting org's own comparable events — exempt from anonymization, shown by name. */
    public record OwnEvent(UUID eventId, String name, Integer attendance, Long grossRevenueMinor,
                           Boolean sellOut, Integer soldTotal, Integer capacity, Instant eventDate) {}

    /**
     * Privacy-preserving aggregate over foreign events. Present ONLY when the foreign cluster
     * is ≥ {@link #MIN_CLUSTER}; attendance rounded to 10, revenue to €100. No ids, no names.
     */
    public record ForeignAggregate(int count, int avgAttendanceRounded, int medianAttendanceRounded,
                                   long avgRevenueMinorRounded, double sellOutRate) {}

    /**
     * The corpus for a segment. {@code densityTotal} (own + foreign) is what the §5 language
     * ladder reads; {@code ownCount} feeds its "≥3 from this organizer" Tier A sub-rule.
     * {@code foreignAggregate} is null when the foreign cluster is suppressed for privacy.
     */
    public record ComparableCorpus(RelaxationLevel appliedRelaxation, int densityTotal,
                                   int ownCount, int foreignCount, List<OwnEvent> ownEvents,
                                   ForeignAggregate foreignAggregate) {
        /** True when a cross-org benchmark can be shown (foreign cluster met the privacy floor). */
        public boolean hasBenchmark() { return foreignAggregate != null; }
    }

    /**
     * Retrieve the comparable corpus for a draft/event's segment.
     *
     * <p>Signature note: the spec segments on city × genre family × capacity band × season;
     * {@code country} rides along because the ladder's first relaxation (city → country) needs
     * it. Callers pass the event's own city+country.
     */
    @Transactional(readOnly = true)
    public ComparableCorpus retrieve(UUID orgId, String city, String country, String genreFamily,
                                     CapacityBand capacityBand, Season season) {
        RelaxationLevel level = RelaxationLevel.NONE;
        List<EventOutcome> cluster = fetch(level, city, country, genreFamily, capacityBand, season);
        while (cluster.size() < MIN_CLUSTER && level.next() != null) {
            level = level.next();
            cluster = fetch(level, city, country, genreFamily, capacityBand, season);
        }

        List<EventOutcome> own = new ArrayList<>();
        List<EventOutcome> foreign = new ArrayList<>();
        for (EventOutcome o : cluster) {
            if (o.getOrgId() != null && o.getOrgId().equals(orgId)) own.add(o);
            else foreign.add(o);
        }

        return new ComparableCorpus(
                level,
                cluster.size(),          // density feeds the language ladder (own + foreign)
                own.size(),
                foreign.size(),
                toOwnEvents(own),
                aggregateForeign(foreign));
    }

    private List<EventOutcome> fetch(RelaxationLevel level, String city, String country,
                                     String genreFamily, CapacityBand band, Season season) {
        return switch (level) {
            case NONE -> (city == null || genreFamily == null || band == null || season == null)
                    ? List.of()
                    : outcomes.findFinalizedByCitySegment(city, genreFamily, band, season);
            // GENRE_TO_FAMILY is identity today (outcomes store only the family), so it queries the
            // same country segment as CITY_TO_COUNTRY — kept in the ladder for order fidelity.
            case CITY_TO_COUNTRY, GENRE_TO_FAMILY -> (country == null || genreFamily == null || band == null || season == null)
                    ? List.of()
                    : outcomes.findFinalizedByCountrySegment(country, genreFamily, band, season);
            case DROP_SEASON -> (country == null || genreFamily == null || band == null)
                    ? List.of()
                    : outcomes.findFinalizedByCountrySegmentNoSeason(country, genreFamily, band);
        };
    }

    private List<OwnEvent> toOwnEvents(List<EventOutcome> own) {
        if (own.isEmpty()) return List.of();
        Map<UUID, String> names = events.findAllById(own.stream().map(EventOutcome::getEventId).toList())
                .stream().collect(Collectors.toMap(Event::getId, Event::getName, (a, b) -> a));
        List<OwnEvent> out = new ArrayList<>(own.size());
        for (EventOutcome o : own) {
            out.add(new OwnEvent(o.getEventId(), names.get(o.getEventId()), o.getAttendance(),
                    o.getGrossRevenueMinor(), o.getSellOut(), o.getSoldTotal(),
                    o.getCapacity(), o.getEventDate()));
        }
        return out;
    }

    /** Aggregate foreign events — ONLY when the cluster meets the ≥5 privacy floor; else null. */
    private ForeignAggregate aggregateForeign(List<EventOutcome> foreign) {
        if (foreign.size() < MIN_CLUSTER) return null; // suppressed — no cluster under 5

        List<Integer> attendance = new ArrayList<>();
        long revenueSum = 0;
        int revenueCount = 0;
        int sellOuts = 0;
        for (EventOutcome o : foreign) {
            if (o.getAttendance() != null) attendance.add(o.getAttendance());
            if (o.getGrossRevenueMinor() != null) { revenueSum += o.getGrossRevenueMinor(); revenueCount++; }
            if (Boolean.TRUE.equals(o.getSellOut())) sellOuts++;
        }

        int avgAtt = attendance.isEmpty() ? 0
                : roundTo(mean(attendance), ATTENDANCE_ROUNDING);
        int medAtt = attendance.isEmpty() ? 0
                : roundTo(median(attendance), ATTENDANCE_ROUNDING);
        long avgRev = revenueCount == 0 ? 0
                : roundToLong((double) revenueSum / revenueCount, REVENUE_ROUNDING_MINOR);
        double sellOutRate = (double) sellOuts / foreign.size();

        return new ForeignAggregate(foreign.size(), avgAtt, medAtt, avgRev, sellOutRate);
    }

    // ---- rounding / stats helpers --------------------------------------------

    private static double mean(List<Integer> xs) {
        double sum = 0;
        for (int x : xs) sum += x;
        return sum / xs.size();
    }

    private static double median(List<Integer> xs) {
        List<Integer> s = new ArrayList<>(xs);
        s.sort(Integer::compareTo);
        int n = s.size();
        return (n % 2 == 1) ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    private static int roundTo(double value, int step) {
        return (int) (Math.round(value / step) * step);
    }

    private static long roundToLong(double value, long step) {
        return Math.round(value / step) * step;
    }
}
