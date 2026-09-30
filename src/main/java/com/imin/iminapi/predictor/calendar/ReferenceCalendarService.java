package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.predictor.service.PublicHolidayCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference calendar reads. Synced rows first; a country-year with no synced holidays falls back
 * to the static {@link PublicHolidayCalendar} (hits with origin {@code fallback} and no source URL).
 */
@Service
public class ReferenceCalendarService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceCalendarService.class);
    static final String HOLIDAY = "holiday";

    private final ReferenceCalendarEntryRepository repository;
    /** country:year already warned about, so an empty table logs once per JVM, not per query. */
    private final Set<String> warnedFallback = ConcurrentHashMap.newKeySet();

    public ReferenceCalendarService(ReferenceCalendarEntryRepository repository) {
        this.repository = repository;
    }

    public List<CalendarHit> on(LocalDate day, CalendarPlace place) {
        return between(day, day, place);
    }

    /** Every fact whose range touches [from, to] for the place's country, region and school zone. */
    public List<CalendarHit> between(LocalDate from, LocalDate to, CalendarPlace place) {
        if (place == null || place.country() == null || place.country().isBlank() || from.isAfter(to)) return List.of();
        String country = place.country();
        List<CalendarHit> out = new ArrayList<>();
        for (ReferenceCalendarEntry e : repository.findOverlapping(country, place.regions(), from, to)) {
            out.add(new CalendarHit(e.getCalendarDate(), e.getEndDate(), e.getKind(), e.getName(), e.getRegion(),
                    e.getSourceUrl(), "hijri".equals(e.getKind()), CalendarHit.SYNCED));
        }
        for (int y = from.getYear(); y <= to.getYear(); y++) {
            if (hasSyncedHolidays(country, y)) continue;
            LocalDate a = from.getYear() == y ? from : LocalDate.of(y, 1, 1);
            LocalDate b = to.getYear() == y ? to : LocalDate.of(y, 12, 31);
            if (warnedFallback.add(country + ":" + y)) {
                log.warn("ReferenceCalendarService: no synced holidays for {} {}, using the static table", country, y);
            }
            out.addAll(fallback(country, place.holidayRegion(), a, b));
        }
        out.sort(Comparator.comparing(CalendarHit::date).thenComparing(CalendarHit::kind).thenComparing(CalendarHit::name));
        return out;
    }

    /** Synced rows of this kind exist for the country in the day's year. */
    public boolean covers(String country, String kind, LocalDate day) {
        if (country == null || kind == null || day == null) return false;
        return repository.existsByCountryAndKindAndCalendarDateBetween(country, kind,
                LocalDate.of(day.getYear(), 1, 1), LocalDate.of(day.getYear(), 12, 31));
    }

    private boolean hasSyncedHolidays(String country, int year) {
        return covers(country, HOLIDAY, LocalDate.of(year, 1, 1));
    }

    /** Static-table holidays in [a, b] (same year); rows only the region has carry that region. */
    private static List<CalendarHit> fallback(String country, String region, LocalDate a, LocalDate b) {
        long days = ChronoUnit.DAYS.between(a, b);
        LocalDate centre = a.plusDays(days / 2);
        int window = (int) (days - days / 2);
        Set<LocalDate> national = new HashSet<>();
        for (PublicHolidayCalendar.Holiday h : PublicHolidayCalendar.near(country, centre, window)) national.add(h.date());
        List<CalendarHit> out = new ArrayList<>();
        for (PublicHolidayCalendar.Holiday h : PublicHolidayCalendar.near(country, region, centre, window)) {
            if (h.date().isBefore(a) || h.date().isAfter(b)) continue;
            String r = national.contains(h.date()) || region == null ? "" : region;
            out.add(new CalendarHit(h.date(), null, HOLIDAY, h.name(), r, null, false, CalendarHit.FALLBACK));
        }
        return out;
    }
}
