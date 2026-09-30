package com.imin.iminapi.predictor.calendar;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calendar rows computed from rules rather than fetched: ponts, DST nights and Hijri dates.
 * Names are stable keys ({@code dst_forward}, {@code eid_al_fitr}, {@code pont:<holiday>}); text is rendered at view time.
 */
public class ComputedCalendar implements CalendarSource {

    // source_url is the writer's scope key: changing a URL orphans rows stored under the old one until cleaned up
    static final String IANA_URL = "https://www.iana.org/time-zones";
    static final String HIJRI_URL =
            "https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/chrono/HijrahChronology.html";

    /** Countries that get computed rows, with the zone their DST follows. Other countries join this map when their holiday source is added. */
    static final Map<String, ZoneId> ZONES = Map.of("FR", ZoneId.of("Europe/Paris"), "NL", ZoneId.of("Europe/Amsterdam"),
            "DE", ZoneId.of("Europe/Berlin"), "ES", ZoneId.of("Europe/Madrid"));

    /** A night whose next day is 23 h (clocks forward) or 25 h (clocks back). */
    public record DstNight(LocalDate night, long hours) {
        public String name() { return hours < 24 ? "dst_forward" : "dst_back"; }
    }

    private final CalendarSyncProperties props;

    public ComputedCalendar(CalendarSyncProperties props) {
        this.props = props;
    }

    @Override
    public String key() { return "computed"; }

    @Override
    public List<Batch> fetch(LocalDate today) {
        LocalDate from = LocalDate.of(today.getYear(), 1, 1);
        LocalDate to = LocalDate.of(today.getYear() + props.getYearsAhead(), 12, 31);
        List<CalendarRow> dst = new ArrayList<>();
        List<CalendarRow> hijri = new ArrayList<>();
        ZONES.forEach((country, zone) -> {
            for (DstNight n : dstNights(zone, from, to)) {
                dst.add(new CalendarRow(country, "", n.night(), null, "dst", n.name(), IANA_URL));
            }
            for (CalendarRow r : hijri(from, to)) {
                hijri.add(new CalendarRow(country, "", r.date(), r.endDate(), r.kind(), r.name(), r.sourceUrl()));
            }
        });
        return List.of(new Batch(IANA_URL, Set.of("dst"), from, to, dst),
                new Batch(HIJRI_URL, Set.of("hijri"), from, to, hijri));
    }

    /**
     * Monday before a Tuesday holiday and Friday after a Thursday holiday, unless that day is
     * itself a holiday. Rows carry the holiday's region and source.
     */
    public static List<CalendarRow> ponts(List<CalendarRow> holidays) {
        Map<LocalDate, CalendarRow> byDate = new LinkedHashMap<>();
        for (CalendarRow h : holidays) byDate.putIfAbsent(h.date(), h);
        List<CalendarRow> out = new ArrayList<>();
        for (CalendarRow h : holidays) {
            DayOfWeek dow = h.date().getDayOfWeek();
            LocalDate pont = dow == DayOfWeek.TUESDAY ? h.date().minusDays(1)
                    : dow == DayOfWeek.THURSDAY ? h.date().plusDays(1) : null;
            if (pont == null || byDate.containsKey(pont)) continue;
            out.add(new CalendarRow(h.country(), h.region(), pont, null, "pont", "pont:" + h.name(), h.sourceUrl()));
        }
        return out;
    }

    /** Nights (local date before the change) of every DST transition in [from, to]. */
    public static List<DstNight> dstNights(ZoneId zone, LocalDate from, LocalDate to) {
        ZoneRules rules = zone.getRules();
        List<DstNight> out = new ArrayList<>();
        Instant cursor = from.atStartOfDay(zone).toInstant().minusSeconds(1);
        ZoneOffsetTransition t;
        while ((t = rules.nextTransition(cursor)) != null) {
            LocalDate sun = t.getDateTimeBefore().toLocalDate();
            LocalDate night = sun.minusDays(1);
            if (night.isAfter(to)) break;
            if (!night.isBefore(from)) {
                long hours = Duration.between(sun.atStartOfDay(zone), sun.plusDays(1).atStartOfDay(zone)).toHours();
                out.add(new DstNight(night, hours));
            }
            cursor = t.getInstant();
        }
        return out;
    }

    /**
     * Ramadan (a range to the day before 1 Shawwal), Eid al-Fitr and Eid al-Adha from the JDK's
     * Umm al-Qura calendar, starting in [from, to]. Local announcements can differ by a day.
     */
    public static List<CalendarRow> hijri(LocalDate from, LocalDate to) {
        int first = HijrahDate.from(from).get(ChronoField.YEAR);
        int last = HijrahDate.from(to).get(ChronoField.YEAR);
        List<CalendarRow> out = new ArrayList<>();
        for (int y = first; y <= last; y++) {
            LocalDate ramadan = LocalDate.from(HijrahDate.of(y, 9, 1));
            LocalDate fitr = LocalDate.from(HijrahDate.of(y, 10, 1));
            LocalDate adha = LocalDate.from(HijrahDate.of(y, 12, 10));
            addIn(out, from, to, new CalendarRow("", "", ramadan, fitr.minusDays(1), "hijri", "ramadan", HIJRI_URL));
            addIn(out, from, to, new CalendarRow("", "", fitr, null, "hijri", "eid_al_fitr", HIJRI_URL));
            addIn(out, from, to, new CalendarRow("", "", adha, null, "hijri", "eid_al_adha", HIJRI_URL));
        }
        return out;
    }

    private static void addIn(List<CalendarRow> out, LocalDate from, LocalDate to, CalendarRow r) {
        if (!r.date().isBefore(from) && !r.date().isAfter(to)) out.add(r);
    }
}
