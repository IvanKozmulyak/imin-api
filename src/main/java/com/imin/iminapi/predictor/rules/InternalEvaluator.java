package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.service.ComparableCorpusService;
import com.imin.iminapi.predictor.service.ComparableCorpusService.ComparableCorpus;
import com.imin.iminapi.predictor.service.CompetingNightsService;
import com.imin.iminapi.predictor.service.CompetingNightsService.CityEvent;
import com.imin.iminapi.predictor.service.PacingCurveService;
import com.imin.iminapi.predictor.service.PacingCurveService.CurveMatch;
import com.imin.iminapi.predictor.service.PacingEngine.CurvePoint;
import com.imin.iminapi.predictor.service.PredictorSegmentKeys;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.util.EventNormalization;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Questions answered from imin's own data: other listings on the night (2.1) and week (2.2), the
 * organizer's own events within ±14 days (2.7), comparable sell-out rates (2.9) and pacing (10.2).
 */
@Component
public class InternalEvaluator implements QuestionEvaluator {

    static final int OWN_EVENT_WINDOW_DAYS = 14;
    static final int CITY_HISTORY_DAYS = 365;
    private static final int WEEK = 7;
    private static final int MIN_ARTIST_CHARS = 3;

    private final CompetingNightsService nights;
    private final EventRepository events;
    private final ComparableCorpusService corpus;
    private final PacingCurveService curves;

    public InternalEvaluator(CompetingNightsService nights, EventRepository events, ComparableCorpusService corpus,
                             PacingCurveService curves) {
        this.nights = nights;
        this.events = events;
        this.corpus = corpus;
        this.curves = curves;
    }

    @Override
    public SourceKind source() { return SourceKind.INTERNAL; }

    @Override
    public Set<String> questionIds() { return Set.of("2.1", "2.2", "2.7", "2.9", "10.2"); }

    @Override
    public Finding evaluate(Question q, DateCheckInput in, LocalDate date) {
        return switch (q.id()) {
            case "2.1", "2.2" -> competition(q, in, date);
            case "2.7" -> ownEvents(q, in, date);
            case "2.9" -> sellOut(q, in, date);
            case "10.2" -> pacing(q, in, date);
            default -> throw new IllegalStateException("InternalEvaluator has no rule for " + q.id());
        };
    }

    /** Other organizers' same-genre listings: 2.1 within a night of d, 2.2 two to seven nights away. */
    private Finding competition(Question q, DateCheckInput in, LocalDate d) {
        ZoneId zone = in.zone();
        String cityKey = in.cityKey();
        if (cityKey.isEmpty()) return Finding.notChecked(q, "not_provided");
        if (!events.existsPublishedInCitySince(cityKey,
                NightDates.nightStart(in.today(), zone).minus(CITY_HISTORY_DAYS, ChronoUnit.DAYS))) {
            return Finding.notChecked(q, "no_imin_events_in_city");
        }
        String genre = EventNormalization.genreKey(in.genreFamily());
        if (genre.isEmpty()) return Finding.notChecked(q, "not_provided");
        boolean night = q.id().equals("2.1");
        List<CityEvent> matches = new ArrayList<>();
        for (CityEvent e : nights.between(cityKey, NightDates.nightStart(d.minusDays(WEEK), zone),
                NightDates.nightStart(d.plusDays(WEEK + 1), zone))) {
            if (e.orgId().equals(in.orgId()) || !genre.equals(e.genreKey())) continue;
            long delta = Math.abs(ChronoUnit.DAYS.between(d, NightDates.nightOf(e.startsAt(), zone)));
            if (night ? delta <= 1 : delta > 1 && delta <= WEEK) matches.add(e);
        }
        if (matches.isEmpty()) return Finding.clear(q);
        CityEvent nearest = matches.stream()
                .min(Comparator.comparingLong((CityEvent e) -> delta(e, d, zone)).thenComparing(CityEvent::startsAt))
                .orElseThrow();
        int strength;
        if (night) {
            strength = delta(nearest, d, zone) == 0 ? 3 : 2;
        } else {
            String sub = in.subGenre() == null ? "" : in.subGenre().trim();
            strength = !sub.isEmpty() && matches.stream().anyMatch(e -> sub.equalsIgnoreCase(norm(e.subGenre()))) ? 3 : 2;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("date", NightDates.nightOf(nearest.startsAt(), zone).toString());
        facts.put("name", nearest.name());
        facts.put("venue", nearest.venueName());
        facts.put("count", matches.size());
        return Finding.found(q, Kind.RISK, strength, facts, null);
    }

    /** The organizer's own non-cancelled events (drafts too) within ±14 nights; shared artists raise strength. */
    private Finding ownEvents(Question q, DateCheckInput in, LocalDate d) {
        ZoneId zone = in.zone();
        String cityKey = in.cityKey();
        if (cityKey.isEmpty()) return Finding.notChecked(q, "not_provided");
        List<Event> own = events.findOrgEventsInCityBetween(in.orgId(), cityKey,
                NightDates.nightStart(d.minusDays(OWN_EVENT_WINDOW_DAYS), zone),
                NightDates.nightStart(d.plusDays(OWN_EVENT_WINDOW_DAYS + 1), zone)).stream()
                .filter(e -> !e.getId().equals(in.excludeEventId()))
                .toList();
        if (own.isEmpty()) return Finding.clear(q);
        List<String> artists = in.lineup().stream().map(String::trim).filter(a -> a.length() >= MIN_ARTIST_CHARS).toList();
        Event pick = null;
        List<String> pickShared = List.of();
        long pickDelta = Long.MAX_VALUE;
        for (Event e : own) {
            List<String> shared = artists.stream().filter(a -> mentions(e, a)).toList();
            long delta = Math.abs(ChronoUnit.DAYS.between(d, NightDates.nightOf(e.getStartsAt(), zone)));
            boolean better = pick == null
                    || (!shared.isEmpty() && pickShared.isEmpty())
                    || (shared.isEmpty() == pickShared.isEmpty() && delta < pickDelta);
            if (better) {
                pick = e;
                pickShared = shared;
                pickDelta = delta;
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("date", NightDates.nightOf(pick.getStartsAt(), zone).toString());
        facts.put("name", pick.getName());
        facts.put("sharedArtists", pickShared);
        return Finding.found(q, Kind.RISK, pickShared.isEmpty() ? 2 : 3, facts, null);
    }

    // ponytail: text match on name and description; a real lineup column would make this exact.
    private static boolean mentions(Event e, String artist) {
        Pattern word = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(artist) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return word.matcher(e.getName() == null ? "" : e.getName()).find()
                || word.matcher(e.getDescription() == null ? "" : e.getDescription()).find();
    }

    /** Pooled sell-out rate of comparable past events; foreign events count only as the privacy-safe aggregate. */
    private Finding sellOut(Question q, DateCheckInput in, LocalDate d) {
        CapacityBand band = CapacityBand.of(in.capacity());
        String genre = PredictorSegmentKeys.genreKey(in.genreFamily());
        if (band == null || genre == null) return Finding.notChecked(q, "not_provided");
        ZoneId zone = in.zone();
        ComparableCorpus c = corpus.retrieve(in.orgId(), PredictorSegmentKeys.cityKey(in.city()), in.country(), genre,
                band, Season.of(NightDates.nightStart(d, zone), zone));
        long ownSellOuts = c.ownEvents().stream().filter(e -> Boolean.TRUE.equals(e.sellOut())).count();
        int n = c.ownCount();
        double sellOuts = ownSellOuts;
        if (c.foreignAggregate() != null) {
            n += c.foreignAggregate().count();
            sellOuts += c.foreignAggregate().sellOutRate() * c.foreignAggregate().count();
        }
        if (n < Params.of(q, "min_events").intValue()) return Finding.notChecked(q, "no_data");
        double rate = sellOuts / n;
        Kind kind;
        if (rate >= Params.of(q, "sell_out_opp_min").doubleValue()) kind = Kind.OPPORTUNITY;
        else if (rate <= Params.of(q, "sell_out_risk_max").doubleValue()) kind = Kind.RISK;
        else return Finding.clear(q);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("sellOutRate", Math.round(rate * 20) / 20.0);
        facts.put("n", n);
        facts.put("relaxation", c.appliedRelaxation().name());
        return Finding.found(q, kind, 2, facts, null);
    }

    /** Share of final sales that comparable events had already made by the time this date would go on sale. */
    private Finding pacing(Question q, DateCheckInput in, LocalDate d) {
        CapacityBand band = CapacityBand.of(in.capacity());
        String genre = PredictorSegmentKeys.genreKey(in.genreFamily());
        if (band == null || genre == null) return Finding.notChecked(q, "not_provided");
        ZoneId zone = in.zone();
        Optional<CurveMatch> match = curves.lookup(PredictorSegmentKeys.cityKey(in.city()), in.country(), genre, band,
                Season.of(NightDates.nightStart(d, zone), zone));
        if (match.isEmpty()) return Finding.notChecked(q, "no_data");
        long lead = ChronoUnit.DAYS.between(in.today(), d);
        double share = match.get().curve().points().stream()
                .filter(p -> p.daysOut() >= lead)
                .min(Comparator.comparingInt(CurvePoint::daysOut))
                .map(CurvePoint::medianPct)
                .orElse(0.0);
        if (share < Params.of(q, "missed_share_min").doubleValue()) return Finding.clear(q);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("leadDays", lead);
        facts.put("soldShareBefore", Math.round(share * 100) / 100.0);
        return Finding.found(q, Kind.RISK, share >= 0.5 ? 3 : 2, facts, null);
    }

    private static long delta(CityEvent e, LocalDate d, ZoneId zone) {
        return Math.abs(ChronoUnit.DAYS.between(d, NightDates.nightOf(e.startsAt(), zone)));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }
}
