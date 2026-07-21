package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.service.ComparableCorpusService.ComparableCorpus;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot.CorpusLine;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot.ForeignAggregateLine;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot.HolidayLine;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot.OwnComparableLine;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot.PromoLine;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot.TierLine;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Feature assembly (spec §7.2 step 1, task 86cav4741): builds the one versioned
 * {@link PredictionInputSnapshot} a score is a pure function of. Sources:
 * draft attributes + tier structure + promo config (the Event aggregate), organizer
 * history (org tenure, prior events), calendar signals (day-of-week, season, static
 * holiday table §6.3), and the comparable-corpus retrieval result (§6.4).
 *
 * <p>Determinism: everything here reads persisted state plus a {@link Clock}; two calls on
 * the same day with an unchanged draft and unchanged corpus produce byte-identical
 * canonical JSON, hence the same SHA-256 — that equality is the whole caching contract.
 */
@Service
public class PredictionInputSnapshotService {

    /** ± window for the "public holiday near the event" signal, in days. */
    static final int HOLIDAY_WINDOW_DAYS = 3;

    private final TicketTierRepository tiers;
    private final PromoCodeRepository promos;
    private final OrganizationRepository orgs;
    private final EventRepository events;
    private final ComparableCorpusService corpus;
    private final Clock clock;

    public PredictionInputSnapshotService(TicketTierRepository tiers, PromoCodeRepository promos,
                                          OrganizationRepository orgs, EventRepository events,
                                          ComparableCorpusService corpus, Clock clock) {
        this.tiers = tiers;
        this.promos = promos;
        this.orgs = orgs;
        this.events = events;
        this.corpus = corpus;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PredictionInputSnapshot build(Event e) {
        ZoneId zone = resolveZone(e.getTimezone());
        Instant startsAt = e.getStartsAt();
        LocalDate eventDay = startsAt == null ? null : startsAt.atZone(zone).toLocalDate();

        int capacity = tiers.sumQuantityByEventId(e.getId());
        CapacityBand band = CapacityBand.of(capacity);
        Season season = Season.of(startsAt, zone);
        String genreFamily = blankToNull(e.getGenre());
        String city = blankToNull(e.getVenueCity());
        String country = blankToNull(e.getVenueCountry());

        // Tier lines in stable sortOrder — the price/quantity structure IS scoring input,
        // so any tier edit changes the hash (the §4.1 "material edit re-scores" rule).
        List<TierLine> tierLines = new ArrayList<>();
        tiers.findByEventIdOrderBySortOrderAsc(e.getId()).forEach(t ->
                tierLines.add(new TierLine(t.getName(), t.getPriceMinor(), t.getQuantity(),
                        iso(t.getSaleStartsAt()), iso(t.getSaleClosesAt()))));

        List<PromoLine> promoLines = new ArrayList<>();
        promos.findByEventId(e.getId()).stream()
                .sorted(Comparator.comparing(p -> p.getCode() == null ? "" : p.getCode()))
                .forEach(p -> promoLines.add(new PromoLine(p.getCode(), p.getDiscountPct(), p.getMaxUses())));

        Organization org = orgs.findById(e.getOrgId()).orElse(null);
        Integer tenureDays = (org == null || org.getCreatedAt() == null) ? null
                : (int) Math.max(0, ChronoUnit.DAYS.between(org.getCreatedAt(), clock.instant()));

        // Calendar signal from the static table (§6.3). Coverage is explicit — see the record doc.
        boolean covered = country != null && eventDay != null && PublicHolidayCalendar.covers(country, eventDay);
        List<HolidayLine> holidayLines = new ArrayList<>();
        if (covered) {
            for (PublicHolidayCalendar.Holiday h : PublicHolidayCalendar.near(country, eventDay, HOLIDAY_WINDOW_DAYS)) {
                holidayLines.add(new HolidayLine(h.date().toString(), h.name()));
            }
        }

        ComparableCorpus cc = corpus.retrieve(e.getOrgId(), city, country, genreFamily, band, season);

        return new PredictionInputSnapshot(
                PredictionInputSnapshot.SNAPSHOT_VERSION,
                e.getId(),
                city,
                country,
                genreFamily,
                capacity,
                band == null ? null : band.name(),
                iso(startsAt),
                startsAt == null ? null : startsAt.atZone(zone).getDayOfWeek().getValue(),
                season == null ? null : season.name(),
                leadTimeDays(eventDay),
                e.getCurrency(),
                tierLines,
                promoLines,
                tenureDays,
                priorEventCount(e),
                covered,
                holidayLines,
                corpusLine(cc));
    }

    /** Whole days from the scoring day to the event day (coarse on purpose — see record doc). */
    private Integer leadTimeDays(LocalDate eventDay) {
        if (eventDay == null) return null;
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("UTC"));
        return (int) ChronoUnit.DAYS.between(today, eventDay);
    }

    /**
     * The org's published-event count before this event. A DRAFT is not yet published, so the
     * raw count IS the prior count; a LIVE/PAST event excludes itself (mirrors the outcome
     * record's publish-freeze definition).
     */
    private Integer priorEventCount(Event e) {
        long published = events.countPublished(e.getOrgId());
        boolean selfCounted = e.getStatus() != null && e.getStatus() != EventStatus.DRAFT;
        return (int) Math.max(0, selfCounted ? published - 1 : published);
    }

    private static CorpusLine corpusLine(ComparableCorpus cc) {
        List<OwnComparableLine> own = new ArrayList<>();
        cc.ownEvents().stream()
                .sorted(Comparator.comparing(o -> o.eventId().toString()))
                .forEach(o -> own.add(new OwnComparableLine(o.eventId(), o.attendance(), o.soldTotal(),
                        o.capacity(), o.sellOut(), o.grossRevenueMinor())));
        ForeignAggregateLine fa = cc.foreignAggregate() == null ? null
                : new ForeignAggregateLine(cc.foreignAggregate().count(),
                        cc.foreignAggregate().avgAttendanceRounded(),
                        cc.foreignAggregate().medianAttendanceRounded(),
                        cc.foreignAggregate().avgRevenueMinorRounded(),
                        cc.foreignAggregate().sellOutRate());
        return new CorpusLine(cc.appliedRelaxation().name(), cc.densityTotal(), cc.ownCount(),
                cc.foreignCount(), own, fa);
    }

    private static String iso(Instant i) {
        return i == null ? null : i.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
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
