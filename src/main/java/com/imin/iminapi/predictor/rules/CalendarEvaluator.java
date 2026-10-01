package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.calendar.CalendarHit;
import com.imin.iminapi.predictor.calendar.CalendarPlace;
import com.imin.iminapi.predictor.calendar.CalendarRegions;
import com.imin.iminapi.predictor.calendar.FootballClubs;
import com.imin.iminapi.predictor.calendar.FootballFixturesSync;
import com.imin.iminapi.predictor.calendar.FootballFixturesSync.FixtureName;
import com.imin.iminapi.predictor.calendar.ReferenceCalendarService;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.service.PublicHolidayCalendar;
import com.imin.iminapi.predictor.sources.SourceGates;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Calendar questions from {@code reference_calendar}: holidays, ponts, DST nights, Ramadan, school holidays,
 * neighbour-country holidays for border cities, and big football matches (3.2, football-data.org fixtures,
 * behind the {@code football} source gate).
 * A kind with no synced data for the country and year answers not_checked, never clear.
 */
@Component
public class CalendarEvaluator implements QuestionEvaluator {

    /** Answered once diaspora and ad-period sources exist. */
    static final Set<String> NO_SOURCE_YET = Set.of("5.1", "10.3");
    private static final Set<String> RULES = Set.of("3.2", "4.1", "4.2", "4.3", "4.4", "4.5", "4.7", "5.2", "7.1");
    private static final String BORDER = "4.5";
    private static final String FOOTBALL = "3.2";
    private static final int WEEK = 7;
    /** A match runs about two hours from kickoff, half-time included. */
    static final int MATCH_MINUTES = 120;
    /** A match ending this long before doors is a screening opportunity, not a clash; product default. */
    static final int SCREENING_LEAD_MINUTES = 180;
    /** Fixtures not re-synced for this long may carry moved kickoffs; the sync runs weekly. */
    static final int FIXTURES_STALE_DAYS = 14;
    private static final int DAY_MINUTES = 24 * 60;
    private static final int NIGHT_END_MINUTES = DAY_MINUTES + NightDates.NIGHT_ROLLOVER_HOUR * 60;

    private final ReferenceCalendarService calendar;
    private final CalendarRegions regions;
    private final SourceGates gates;

    public CalendarEvaluator(ReferenceCalendarService calendar, CalendarRegions regions, SourceGates gates) {
        this.calendar = calendar;
        this.regions = regions;
        this.gates = gates;
    }

    @Override
    public SourceKind source() { return SourceKind.STRUCTURED; }

    @Override
    public Set<String> questionIds() {
        Set<String> ids = new HashSet<>(RULES);
        ids.addAll(NO_SOURCE_YET);
        return Set.copyOf(ids);
    }

    @Override
    public Finding evaluate(Question q, DateCheckInput in, LocalDate date) {
        return evaluateAll(List.of(q), in, date).get(0);
    }

    /** One calendar read of d±7 serves every question of the date. */
    @Override
    public List<Finding> evaluateAll(List<Question> questions, DateCheckInput in, LocalDate date) {
        Context ctx = null;
        List<Finding> out = new ArrayList<>(questions.size());
        for (Question q : questions) {
            if (NO_SOURCE_YET.contains(q.id())) {
                out.add(Finding.notChecked(q, "no_source"));
                continue;
            }
            if (BORDER.equals(q.id())) {
                out.add(neighbourHoliday(q, in, date));
                continue;
            }
            if (FOOTBALL.equals(q.id())) {
                out.add(football(q, in, date));
                continue;
            }
            if (ctx == null) ctx = load(in, date);
            out.add(answer(q, ctx));
        }
        return out;
    }

    private record Context(CalendarPlace place, LocalDate date, List<CalendarHit> hits) {}

    private Context load(DateCheckInput in, LocalDate date) {
        CalendarPlace place = regions.of(in.country(), in.postalCode(), in.city());
        List<CalendarHit> hits = calendar.between(date.minusDays(WEEK), date.plusDays(WEEK), place);
        return new Context(place, date, hits);
    }

    private Finding answer(Question q, Context c) {
        String country = c.place().country();
        LocalDate d = c.date();
        LocalDate weekFrom = d.minusDays(WEEK);
        LocalDate weekTo = d.plusDays(WEEK);
        return switch (q.id()) {
            case "4.1" -> !holidaysCovered(country, weekFrom, weekTo) ? noData(q)
                    : week(q, Kind.RISK, c, h -> isKind(h, "holiday") && h.region().isEmpty());
            case "4.2" -> !holidaysCovered(country, d, d.plusDays(1)) ? noData(q) : eveOrDayOff(q, c);
            // A regional place also reads holidays to drop a national pont on its own day off.
            case "4.3" -> !covered(country, "pont", d, d.plusDays(1))
                    || (c.place().holidayRegion() != null && !holidaysCovered(country, d, d.plusDays(1)))
                    ? noData(q) : pont(q, c);
            case "4.4" -> !holidaysCovered(country, d.minusDays(1), d.plusDays(1)) ? noData(q) : regionalHoliday(q, c);
            case "4.7" -> !covered(country, "dst", d, d) ? noData(q) : dst(q, c);
            case "5.2" -> !covered(country, "hijri", weekFrom, weekTo) ? noData(q)
                    : week(q, Kind.RISK, c, h -> isKind(h, "hijri") && "ramadan".equals(h.name()));
            case "7.1" -> {
                String zone = c.place().schoolZone();
                if (zone == null || !covered(country, "school", weekFrom, weekTo)) yield noData(q);
                yield week(q, Kind.RISK, c, h -> isKind(h, "school") && zone.equals(h.region()));
            }
            default -> throw new IllegalStateException("CalendarEvaluator has no rule for " + q.id());
        };
    }

    /**
     * A holiday across the border brings guests: strength 3 when the neighbour's next day is off
     * (the eve of it), 2 when only the night's own day is.
     */
    private Finding neighbourHoliday(Question q, DateCheckInput in, LocalDate d) {
        Map<String, List<String>> neighbours = CalendarRegions.neighbours(in.city());
        if (neighbours.isEmpty()) return Finding.notChecked(q, "no_source");
        LocalDate next = d.plusDays(1);
        for (String nc : neighbours.keySet()) {
            if (!covered(nc, "holiday", d, next)) return noData(q);
        }
        List<CalendarHit> hits = new ArrayList<>();
        Map<CalendarHit, String> countryOf = new HashMap<>();
        for (Map.Entry<String, List<String>> e : new TreeMap<>(neighbours).entrySet()) {
            for (String region : e.getValue()) {
                CalendarPlace place = new CalendarPlace(e.getKey(), region.isEmpty() ? null : region, null);
                for (CalendarHit h : calendar.between(d, next, place)) {
                    if (!isKind(h, "holiday")) continue;
                    hits.add(h);
                    countryOf.putIfAbsent(h, e.getKey());
                }
            }
        }
        List<CalendarHit> offNext = hits.stream().filter(h -> covers(h, next)).toList();
        List<CalendarHit> offToday = hits.stream().filter(h -> covers(h, d)).toList();
        Function<CalendarHit, Map<String, Object>> country = h -> Map.of("country", countryOf.get(h));
        if (!offNext.isEmpty()) return found(q, Kind.OPPORTUNITY, 3, offNext, next, country);
        if (!offToday.isEmpty()) return found(q, Kind.OPPORTUNITY, 2, offToday, d, country);
        return Finding.clear(q);
    }

    private record Fixture(CalendarHit hit, FixtureName name, Kind kind) {
        boolean cl() { return "CL".equals(name.competition()); }
    }

    /**
     * A big match on the night: the city's own Ligue 1 club, or a Champions League match of any French club.
     * Risk when it overlaps the event or its kickoff or the start hour is unknown; opportunity when it ends
     * within {@link #SCREENING_LEAD_MINUTES} before doors. Strength 3 for CL, 2 for Ligue 1. A TBC match is a
     * matchday range and counts as risk on every day of it. Fixtures not synced for 14 days answer stale.
     */
    private Finding football(Question q, DateCheckInput in, LocalDate d) {
        if (!"FR".equals(in.country())) return Finding.notChecked(q, "no_source");
        if (!gates.isOn("football")) return Finding.notChecked(q, "source_off");
        Optional<Instant> synced = calendar.lastSynced("FR", "fixture");
        if (synced.isEmpty()) return noData(q);
        if (synced.get().atZone(ZoneOffset.UTC).toLocalDate().isBefore(in.today().minusDays(FIXTURES_STALE_DAYS))) {
            return Finding.notChecked(q, "stale");
        }
        Optional<LocalDate> latest = calendar.latest("FR", "fixture");
        if (latest.isEmpty() || latest.get().isBefore(d)) return noData(q);
        Set<Integer> clubs = FootballClubs.of(in.cityKey());
        List<Fixture> counted = new ArrayList<>();
        for (CalendarHit h : calendar.between(d, d, new CalendarPlace("FR", null, null))) {
            if (!isKind(h, "fixture") || !covers(h, d)) continue;
            FixtureName f;
            try {
                f = FixtureName.parse(h.name());
            } catch (IllegalArgumentException e) {
                continue;
            }
            boolean relevant = "CL".equals(f.competition()) || clubs.contains(f.homeId()) || clubs.contains(f.awayId());
            if (!relevant) continue;
            Kind kind = matchKind(f.kickoff(), in.startHour(), in.endHour());
            if (kind != null) counted.add(new Fixture(h, f, kind));
        }
        if (counted.isEmpty()) return Finding.clear(q);
        Kind kind = counted.stream().anyMatch(f -> f.kind() == Kind.RISK) ? Kind.RISK : Kind.OPPORTUNITY;
        List<Fixture> ofKind = counted.stream().filter(f -> f.kind() == kind).toList();
        Fixture chosen = ofKind.stream().min(Comparator.comparing((Fixture f) -> !f.cl())
                .thenComparing(f -> f.name().kickoff(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(f -> f.name().label())).orElseThrow();
        Map<String, Object> facts = new LinkedHashMap<>();
        // a TBC match carries its matchday range, never a single day it is not known to be on
        facts.put("date", chosen.hit().date().toString());
        facts.put("endDate", chosen.hit().endDate() == null ? null : chosen.hit().endDate().toString());
        facts.put("name", chosen.name().label());
        facts.put("competition", chosen.name().competition());
        facts.put("kickoff", chosen.name().kickoff() == null ? null : chosen.name().kickoff().toString());
        facts.put("count", ofKind.size());
        int strength = ofKind.stream().anyMatch(Fixture::cl) ? 3 : 2;
        return Finding.found(q, kind, strength, facts, FootballFixturesSync.PUBLIC_URL);
    }

    /** Minutes from the night's midnight-before; hours before the 06:00 rollover belong to the next calendar day. */
    private static int nightMinutes(int hour, int minute) {
        return (hour < NightDates.NIGHT_ROLLOVER_HOUR ? hour + 24 : hour) * 60 + minute;
    }

    /** RISK, OPPORTUNITY, or null when the match does not touch the event. */
    static Kind matchKind(LocalTime kickoff, Integer startHour, Integer endHour) {
        if (kickoff == null || startHour == null) return Kind.RISK;
        int start = nightMinutes(startHour, 0);
        int end = endHour == null ? NIGHT_END_MINUTES : nightMinutes(endHour, 0);
        // an end that reads as before the start (e.g. 22 → 21) runs to the night's end
        if (end <= start) end = NIGHT_END_MINUTES;
        int k = nightMinutes(kickoff.getHour(), kickoff.getMinute());
        int matchEnd = k + MATCH_MINUTES;
        if (k < end && matchEnd > start) return Kind.RISK;
        if (matchEnd <= start && matchEnd >= start - SCREENING_LEAD_MINUTES) return Kind.OPPORTUNITY;
        return null;
    }

    /** Coverage is per year, so a window crossing New Year needs both years synced. */
    private boolean covered(String country, String kind, LocalDate from, LocalDate to) {
        return calendar.covers(country, kind, from) && (from.getYear() == to.getYear() || calendar.covers(country, kind, to));
    }

    private boolean holidaysCovered(String country, LocalDate from, LocalDate to) {
        return holidaysCovered(country, from) && (from.getYear() == to.getYear() || holidaysCovered(country, to));
    }

    private boolean holidaysCovered(String country, LocalDate d) {
        return calendar.covers(country, "holiday", d) || PublicHolidayCalendar.covers(country, d);
    }

    private static Finding noData(Question q) {
        return Finding.notChecked(q, "no_data");
    }

    /** Hits of the kind overlapping d±7; strength 3 when one covers d itself. */
    private static Finding week(Question q, Kind kind, Context c, Predicate<CalendarHit> match) {
        LocalDate d = c.date();
        List<CalendarHit> hits = c.hits().stream()
                .filter(match)
                .filter(h -> overlaps(h, d.minusDays(WEEK), d.plusDays(WEEK)))
                .toList();
        if (hits.isEmpty()) return Finding.clear(q);
        return found(q, kind, hits.stream().anyMatch(h -> covers(h, d)) ? 3 : 2, hits, d);
    }

    /** Opportunity when the next day is a day off; risk when d is a holiday and the next day a working day. */
    private static Finding eveOrDayOff(Question q, Context c) {
        LocalDate d = c.date();
        LocalDate next = d.plusDays(1);
        List<CalendarHit> offNext = c.hits().stream()
                .filter(h -> (isKind(h, "holiday") || isKind(h, "pont")) && covers(h, next))
                .toList();
        // Both branches hit the question's own target day, hence strength 3.
        if (!offNext.isEmpty()) return found(q, Kind.OPPORTUNITY, 3, offNext, next);
        List<CalendarHit> holidayToday = c.hits().stream()
                .filter(h -> isKind(h, "holiday") && covers(h, d))
                .toList();
        boolean nextIsWorkday = next.getDayOfWeek() != DayOfWeek.SATURDAY && next.getDayOfWeek() != DayOfWeek.SUNDAY;
        if (!holidayToday.isEmpty() && nextIsWorkday) return found(q, Kind.RISK, 3, holidayToday, d);
        return Finding.clear(q);
    }

    /** A pont on d or d+1; a national pont on a day the place's region already has off does not count. */
    private static Finding pont(Question q, Context c) {
        LocalDate d = c.date();
        String region = c.place().holidayRegion();
        List<CalendarHit> ponts = c.hits().stream()
                .filter(h -> isKind(h, "pont") && (covers(h, d) || covers(h, d.plusDays(1))))
                .filter(h -> !(h.region().isEmpty() && regionalHolidayOn(c, region, h.date())))
                .toList();
        if (ponts.isEmpty()) return Finding.clear(q);
        return found(q, Kind.RISK, ponts.stream().anyMatch(h -> covers(h, d)) ? 3 : 2, ponts, d);
    }

    private static boolean regionalHolidayOn(Context c, String region, LocalDate day) {
        return region != null && c.hits().stream()
                .anyMatch(h -> isKind(h, "holiday") && region.equals(h.region()) && covers(h, day));
    }

    private static Finding regionalHoliday(Question q, Context c) {
        String region = c.place().holidayRegion();
        if (region == null) return Finding.clear(q);
        LocalDate d = c.date();
        List<CalendarHit> hits = c.hits().stream()
                .filter(h -> isKind(h, "holiday") && region.equals(h.region()) && overlaps(h, d.minusDays(1), d.plusDays(1)))
                .toList();
        if (hits.isEmpty()) return Finding.clear(q);
        return found(q, Kind.RISK, hits.stream().anyMatch(h -> covers(h, d)) ? 3 : 2, hits, d);
    }

    /** The night before clocks go back is an hour longer (opportunity); forward, an hour shorter (risk). */
    private static Finding dst(Question q, Context c) {
        LocalDate d = c.date();
        List<CalendarHit> hits = c.hits().stream().filter(h -> isKind(h, "dst") && h.date().equals(d)).toList();
        if (hits.isEmpty()) return Finding.clear(q);
        Kind kind = hits.stream().anyMatch(h -> "dst_forward".equals(h.name())) ? Kind.RISK : Kind.OPPORTUNITY;
        return found(q, kind, 3, hits.stream().filter(h -> kind == Kind.RISK
                ? "dst_forward".equals(h.name()) : "dst_back".equals(h.name())).toList(), d);
    }

    /** Facts of the hit nearest to {@code target}; an approximate (computed Hijri) hit caps strength at 2. */
    private static Finding found(Question q, Kind kind, int strength, List<CalendarHit> matched, LocalDate target) {
        return found(q, kind, strength, matched, target, h -> Map.of());
    }

    /** As above, plus facts {@code extra} derives from the chosen hit. */
    private static Finding found(Question q, Kind kind, int strength, List<CalendarHit> matched, LocalDate target,
                                 Function<CalendarHit, Map<String, Object>> extra) {
        List<CalendarHit> hits = distinct(matched);
        CalendarHit nearest = hits.stream()
                .min(Comparator.comparingLong((CalendarHit h) -> distance(h, target)).thenComparing(CalendarHit::date))
                .orElseThrow();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("date", nearest.date().toString());
        facts.put("endDate", nearest.endDate() == null ? null : nearest.endDate().toString());
        facts.put("name", nearest.name());
        facts.put("count", hits.size());
        facts.putAll(extra.apply(nearest));
        int s = strength;
        if (hits.stream().anyMatch(CalendarHit::approximate)) {
            s = Math.min(s, 2);
            facts.put("approximate", true);
        }
        return Finding.found(q, kind, s, facts, nearest.sourceUrl());
    }

    /** The same fact stored for several regions counts once; applied after each rule's region filter. */
    private static List<CalendarHit> distinct(List<CalendarHit> hits) {
        Map<String, CalendarHit> byFact = new LinkedHashMap<>();
        for (CalendarHit h : hits) byFact.putIfAbsent(h.kind() + "|" + h.name() + "|" + h.date(), h);
        return List.copyOf(byFact.values());
    }

    private static boolean isKind(CalendarHit h, String kind) {
        return kind.equals(h.kind());
    }

    private static LocalDate end(CalendarHit h) {
        return h.endDate() == null ? h.date() : h.endDate();
    }

    private static boolean covers(CalendarHit h, LocalDate day) {
        return !day.isBefore(h.date()) && !day.isAfter(end(h));
    }

    private static boolean overlaps(CalendarHit h, LocalDate from, LocalDate to) {
        return !h.date().isAfter(to) && !end(h).isBefore(from);
    }

    private static long distance(CalendarHit h, LocalDate day) {
        if (covers(h, day)) return 0;
        return day.isBefore(h.date()) ? ChronoUnit.DAYS.between(day, h.date()) : ChronoUnit.DAYS.between(end(h), day);
    }
}
