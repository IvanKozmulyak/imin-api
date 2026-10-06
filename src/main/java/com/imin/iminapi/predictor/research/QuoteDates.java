package com.imin.iminapi.predictor.research;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * French and English dates in a quoted line (ISO, 17/10/2026, "sam. 17 oct.", "October 17th, 2026", "du 16 au 18
 * octobre"), and how they sit against a candidate night. A weekday that contradicts the date's year drops the date.
 */
public final class QuoteDates {

    /** A date as written; {@code year} null when the text gives none, {@code weekday} null when none is named. */
    public record QuoteDate(Integer year, int month, int day, DayOfWeek weekday) {}

    /** The dates within the window, nearest first, and whether the text reads as an earlier year's page. */
    public record Classified(List<LocalDate> inWindow, boolean stale) {
        public Classified {
            inWindow = List.copyOf(inWindow);
        }
    }

    private static final Map<String, Integer> MONTHS = new LinkedHashMap<>();
    private static final Map<String, DayOfWeek> WEEKDAYS = new LinkedHashMap<>();

    static {
        month(1, "jan", "janv", "janvier", "january");
        month(2, "feb", "fev", "fevr", "fevrier", "february");
        month(3, "mar", "mars", "march");
        month(4, "apr", "avr", "avril", "april");
        month(5, "may", "mai");
        month(6, "jun", "juin", "june");
        month(7, "jul", "juil", "juillet", "july");
        month(8, "aug", "aou", "aout", "august");
        month(9, "sep", "sept", "septembre", "september");
        month(10, "oct", "octobre", "october");
        month(11, "nov", "novembre", "november");
        month(12, "dec", "decembre", "december");
        weekday(DayOfWeek.SUNDAY, "dimanche", "dim", "sunday", "sun");
        weekday(DayOfWeek.MONDAY, "lundi", "lun", "monday", "mon");
        weekday(DayOfWeek.TUESDAY, "mardi", "tuesday", "tue", "tues");
        weekday(DayOfWeek.WEDNESDAY, "mercredi", "mer", "wednesday", "wed");
        weekday(DayOfWeek.THURSDAY, "jeudi", "jeu", "thursday", "thu", "thur", "thurs");
        weekday(DayOfWeek.FRIDAY, "vendredi", "ven", "friday", "fri");
        weekday(DayOfWeek.SATURDAY, "samedi", "sam", "saturday", "sat");
    }

    private static final String MONTH_RE = alternation(MONTHS.keySet());
    private static final String WD_RE = alternation(WEEKDAYS.keySet());
    private static final Pattern ISO = Pattern.compile("\\b(20\\d\\d)-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern SLASHED = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b");
    /** Dotted needs a year: "21.05" or "12.10 €" is a time or a price as often as a date. */
    private static final Pattern DOTTED = Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})\\b");
    private static final Pattern DAY_MONTH = Pattern.compile("(?:\\b(" + WD_RE + ")\\.?,?\\s+)?\\b(\\d{1,2})"
            + "(?:er|st|nd|rd|th)?(?:\\s*(?:-|–|au|to|&|et)\\s*(\\d{1,2})(?:er|st|nd|rd|th)?)?\\s+(" + MONTH_RE
            + ")\\.?(?:\\s*,?\\s*(20\\d\\d))?\\b");
    private static final Pattern MONTH_DAY = Pattern.compile("(?:\\b(" + WD_RE + ")\\.?,?\\s+)?\\b(" + MONTH_RE
            + ")\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:\\s*(?:-|–)\\s*(\\d{1,2}))?(?:\\s*,?\\s*(20\\d\\d))?\\b");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** How far back a weekday may point before a year-less date counts as a stale page. */
    private static final int STALE_YEARS_BACK = 3;

    private QuoteDates() {}

    public static List<QuoteDate> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        String t = fold(text);
        List<QuoteDate> out = new ArrayList<>();
        Matcher m = ISO.matcher(t);
        while (m.find()) add(out, num(m.group(1)), num(m.group(2)), num(m.group(3)), null);
        for (Pattern numeric : List.of(SLASHED, DOTTED)) {
            m = numeric.matcher(t);
            while (m.find()) {
                Integer y = m.group(3) == null ? null : num(m.group(3));
                if (y != null && y < 100) y += 2000;
                add(out, y, num(m.group(2)), num(m.group(1)), null);
            }
        }
        m = DAY_MONTH.matcher(t);
        while (m.find()) {
            Integer y = m.group(5) == null ? null : num(m.group(5));
            int month = MONTHS.get(m.group(4));
            add(out, y, month, num(m.group(2)), m.group(1) == null ? null : WEEKDAYS.get(m.group(1)));
            if (m.group(3) != null) add(out, y, month, num(m.group(3)), null);
        }
        m = MONTH_DAY.matcher(t);
        while (m.find()) {
            Integer y = m.group(5) == null ? null : num(m.group(5));
            int month = MONTHS.get(m.group(2));
            add(out, y, month, num(m.group(3)), m.group(1) == null ? null : WEEKDAYS.get(m.group(1)));
            if (m.group(4) != null) add(out, y, month, num(m.group(4)), null);
        }
        return List.copyOf(out);
    }

    /**
     * Dates within {@code windowDays} of the candidate; a year-less date takes the nearest year that fits. Stale: an
     * explicit earlier year with no explicit-year date in the window, or a weekday that only fits an earlier year.
     */
    public static Classified classify(String text, LocalDate candidate, int windowDays) {
        TreeSet<LocalDate> inWindow = new TreeSet<>(Comparator
                .comparingLong((LocalDate d) -> Math.abs(ChronoUnit.DAYS.between(candidate, d)))
                .thenComparing(Comparator.naturalOrder()));
        boolean explicitInWindow = false;
        boolean pastYear = false;
        boolean weekdayStale = false;
        int cy = candidate.getYear();
        for (QuoteDate q : extract(text)) {
            if (q.year() != null) {
                LocalDate d = date(q.year(), q.month(), q.day());
                if (d == null || (q.weekday() != null && d.getDayOfWeek() != q.weekday())) continue;
                if (within(d, candidate, windowDays)) {
                    inWindow.add(d);
                    explicitInWindow = true;
                } else if (q.year() < cy) {
                    pastYear = true;
                }
                continue;
            }
            for (int y = cy - 1; y <= cy + 1; y++) {
                LocalDate d = date(y, q.month(), q.day());
                if (d == null || !within(d, candidate, windowDays)) continue;
                if (q.weekday() != null && d.getDayOfWeek() != q.weekday()) {
                    if (fitsEarlierYear(q, cy)) weekdayStale = true;
                    continue;
                }
                inWindow.add(d);
            }
        }
        boolean stale = (pastYear && !explicitInWindow) || (weekdayStale && inWindow.isEmpty());
        return new Classified(stale ? List.of() : new ArrayList<>(inWindow), stale);
    }

    /** A folded month or weekday name, as {@link #extract} reads them. */
    static boolean isDateWord(String folded) {
        return MONTHS.containsKey(folded) || WEEKDAYS.containsKey(folded);
    }

    /** Lower-case, accents removed; shared with the validator so both read text the same way. */
    static String fold(String s) {
        return MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static boolean fitsEarlierYear(QuoteDate q, int cy) {
        for (int y = cy - STALE_YEARS_BACK; y < cy; y++) {
            LocalDate d = date(y, q.month(), q.day());
            if (d != null && d.getDayOfWeek() == q.weekday()) return true;
        }
        return false;
    }

    private static boolean within(LocalDate d, LocalDate candidate, int windowDays) {
        return Math.abs(ChronoUnit.DAYS.between(candidate, d)) <= windowDays;
    }

    private static void add(List<QuoteDate> out, Integer y, int month, int day, DayOfWeek weekday) {
        if (month < 1 || month > 12 || day < 1 || day > Month.of(month).maxLength()) return;
        if (y != null && (y < 2000 || y > 2100)) return;
        out.add(new QuoteDate(y, month, day, weekday));
    }

    private static LocalDate date(int y, int month, int day) {
        return day <= Month.of(month).length(java.time.Year.isLeap(y)) ? LocalDate.of(y, month, day) : null;
    }

    private static int num(String s) {
        return Integer.parseInt(s);
    }

    private static void month(int n, String... names) {
        for (String name : names) MONTHS.put(name, n);
    }

    private static void weekday(DayOfWeek d, String... names) {
        for (String name : names) WEEKDAYS.put(name, d);
    }

    /** Longest first, so "octobre" wins over "oct". */
    private static String alternation(java.util.Collection<String> words) {
        return words.stream().sorted(Comparator.comparingInt(String::length).reversed())
                .reduce((a, b) -> a + "|" + b).orElseThrow();
    }
}
