package com.imin.iminapi.predictor.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * STATIC public-holiday table for the predictor's calendar signal (spec §6.3: "calendar data
 * is a static table" — explicitly no external API). Major national holidays for imin's launch
 * markets — FR, NL, DE, ES, UA — hardcoded for 2026–2027.
 *
 * <p>Scope honesty:
 * <ul>
 *   <li>This is a <b>demand signal</b>, not a legal reference: national majors only, no regional
 *       holidays (e.g. German Länder days), no substitute-day rules.</li>
 *   <li>Coverage is explicit: {@link #covers} says whether the table can speak for a
 *       country/date at all. Callers must distinguish "no holidays near the date" (covered,
 *       empty result) from "table has no data" (not covered) — the prompt lists the latter as
 *       unknown instead of silently implying a holiday-free week.</li>
 *   <li>UA note: martial law currently suspends statutory non-working days; the dates remain
 *       culturally load-bearing for event demand, which is what the predictor cares about.</li>
 * </ul>
 *
 * <p>Movable feasts used (verifiable calendar facts):
 * Western Easter Sunday 2026-04-05 / 2027-03-28 (Good Friday −2, Easter Monday +1,
 * Ascension +39, Whit Monday +50); Orthodox (Julian-computus) Easter Sunday 2026-04-12 /
 * 2027-05-02, Trinity Monday +50.
 *
 * <p>Extending coverage = appending rows here (and bumping the snapshot version if the change
 * should invalidate cached scores — see {@code PredictionInputSnapshot#SNAPSHOT_VERSION}).
 */
public final class PublicHolidayCalendar {

    /** One holiday hit near an event date. */
    public record Holiday(LocalDate date, String name) {}

    private static final int MIN_YEAR = 2026;
    private static final int MAX_YEAR = 2027;

    /** country (ISO-3166 alpha-2, upper) → date → name. TreeMap for cheap range scans. */
    private static final Map<String, TreeMap<LocalDate, String>> TABLE = new HashMap<>();

    private static void put(String country, int year, int month, int day, String name) {
        TABLE.computeIfAbsent(country, k -> new TreeMap<>()).put(LocalDate.of(year, month, day), name);
    }

    private static void putBoth(String country, int month, int day, String name) {
        put(country, 2026, month, day, name);
        put(country, 2027, month, day, name);
    }

    static {
        // Western movable feasts. Easter Sunday: 2026-04-05, 2027-03-28.
        LocalDate easter26 = LocalDate.of(2026, 4, 5);
        LocalDate easter27 = LocalDate.of(2027, 3, 28);
        for (String c : List.of("FR", "NL", "DE", "ES")) {
            for (LocalDate easter : List.of(easter26, easter27)) {
                int y = easter.getYear();
                if (!c.equals("FR")) put(c, y, easter.minusDays(2).getMonthValue(), easter.minusDays(2).getDayOfMonth(), "Good Friday");
                if (!c.equals("ES")) put(c, y, easter.plusDays(1).getMonthValue(), easter.plusDays(1).getDayOfMonth(), "Easter Monday");
                if (!c.equals("ES")) put(c, y, easter.plusDays(39).getMonthValue(), easter.plusDays(39).getDayOfMonth(), "Ascension Day");
                if (!c.equals("ES")) put(c, y, easter.plusDays(50).getMonthValue(), easter.plusDays(50).getDayOfMonth(), "Whit Monday");
            }
        }

        // FR — fixed majors.
        putBoth("FR", 1, 1, "Jour de l'an");
        putBoth("FR", 5, 1, "Fête du Travail");
        putBoth("FR", 5, 8, "Victoire 1945");
        putBoth("FR", 7, 14, "Fête nationale");
        putBoth("FR", 8, 15, "Assomption");
        putBoth("FR", 11, 1, "Toussaint");
        putBoth("FR", 11, 11, "Armistice 1918");
        putBoth("FR", 12, 25, "Noël");

        // NL — fixed majors. King's Day stays Apr 27 both years (moves to the 26th only when
        // the 27th is a Sunday: 2026 → Monday, 2027 → Tuesday).
        putBoth("NL", 1, 1, "Nieuwjaarsdag");
        putBoth("NL", 4, 27, "Koningsdag");
        putBoth("NL", 5, 5, "Bevrijdingsdag");
        putBoth("NL", 12, 25, "Eerste Kerstdag");
        putBoth("NL", 12, 26, "Tweede Kerstdag");

        // DE — fixed nationwide majors (regional holidays deliberately out of scope).
        putBoth("DE", 1, 1, "Neujahr");
        putBoth("DE", 5, 1, "Tag der Arbeit");
        putBoth("DE", 10, 3, "Tag der Deutschen Einheit");
        putBoth("DE", 12, 25, "1. Weihnachtstag");
        putBoth("DE", 12, 26, "2. Weihnachtstag");

        // ES — fixed nationwide majors.
        putBoth("ES", 1, 1, "Año Nuevo");
        putBoth("ES", 1, 6, "Epifanía del Señor");
        putBoth("ES", 5, 1, "Fiesta del Trabajo");
        putBoth("ES", 8, 15, "Asunción de la Virgen");
        putBoth("ES", 10, 12, "Fiesta Nacional de España");
        putBoth("ES", 11, 1, "Todos los Santos");
        putBoth("ES", 12, 6, "Día de la Constitución");
        putBoth("ES", 12, 8, "Inmaculada Concepción");
        putBoth("ES", 12, 25, "Navidad");

        // UA — post-2023-reform calendar (Dec 25 Christmas; May 8 Remembrance; Jul 15
        // Statehood; Oct 1 Defenders). Orthodox Easter 2026-04-12 / 2027-05-02.
        putBoth("UA", 1, 1, "New Year");
        putBoth("UA", 3, 8, "International Women's Day");
        put("UA", 2026, 4, 12, "Easter (Orthodox)");
        put("UA", 2027, 5, 2, "Easter (Orthodox)");
        putBoth("UA", 5, 1, "Labour Day");
        putBoth("UA", 5, 8, "Day of Remembrance and Victory");
        put("UA", 2026, 6, 1, "Trinity Monday");   // Orthodox Easter +50
        put("UA", 2027, 6, 21, "Trinity Monday");
        putBoth("UA", 6, 28, "Constitution Day");
        putBoth("UA", 7, 15, "Statehood Day");
        putBoth("UA", 8, 24, "Independence Day");
        putBoth("UA", 10, 1, "Defenders Day");
        putBoth("UA", 12, 25, "Christmas");
    }

    private PublicHolidayCalendar() {}

    /** True when the table can speak for this country/date (supported market + covered year). */
    public static boolean covers(String country, LocalDate date) {
        if (country == null || date == null) return false;
        return TABLE.containsKey(country.toUpperCase())
                && date.getYear() >= MIN_YEAR && date.getYear() <= MAX_YEAR;
    }

    /**
     * Holidays within ±{@code windowDays} of {@code date} for the country, date-ordered.
     * Empty when nothing is near OR when not covered — call {@link #covers} to tell apart.
     */
    public static List<Holiday> near(String country, LocalDate date, int windowDays) {
        if (!covers(country, date)) return List.of();
        TreeMap<LocalDate, String> days = TABLE.get(country.toUpperCase());
        List<Holiday> out = new ArrayList<>();
        days.subMap(date.minusDays(windowDays), true, date.plusDays(windowDays), true)
                .forEach((d, name) -> out.add(new Holiday(d, name)));
        return out;
    }
}
