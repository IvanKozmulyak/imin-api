package com.imin.iminapi.marketing.kit;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The words and dates on the kit images and in the caption's facts line, for en / fr / es / uk.
 * Pure: every date and hour is read in the event's own zone, never the server's.
 */
public final class StoryKitText {

    public static final List<String> LOCALES = List.of("en", "fr", "es", "uk");
    public static final String KEY_STORY = "story";
    public static final String KEY_POST = "post";
    public static final String KEY_D7 = "d7";
    public static final String KEY_D2 = "d2";
    public static final String KEY_D0 = "d0";

    /** A local start at or after this hour reads "Tonight", earlier "Today". */
    static final int EVENING_HOUR = 17;
    /** A local start before this hour belongs to the previous evening: 00:30 Sunday is Saturday night. */
    static final int NIGHT_END_HOUR = 6;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private StoryKitText() {}

    /** A countdown frame and the event-zone day it should be posted on. */
    public record Frame(String key, LocalDate postOn) {}

    /** Frames whose posting day is today or later, and the ones whose day has already passed. */
    public record Plan(List<Frame> made, List<Frame> skipped) {}

    public static boolean supported(String locale) {
        return locale != null && LOCALES.contains(locale);
    }

    /** Image language from the organization's country; anything not French, Spanish or Ukrainian reads English. */
    public static String defaultLocale(String country) {
        if (country == null) return "en";
        return switch (country.trim().toUpperCase(Locale.ROOT)) {
            case "FR" -> "fr";
            case "ES" -> "es";
            case "UA" -> "uk";
            default -> "en";
        };
    }

    /** The event's zone, or UTC when it is missing or unknown. */
    public static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException bad) {
            return ZoneId.of("UTC");
        }
    }

    /** The event-zone day whose night the event starts in. */
    public static LocalDate nightOf(Instant startsAt, ZoneId zone) {
        ZonedDateTime local = startsAt.atZone(zone);
        return local.getHour() < NIGHT_END_HOUR ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    public static Plan frames(Instant startsAt, ZoneId zone, Instant now) {
        LocalDate eventDay = nightOf(startsAt, zone);
        LocalDate today = now.atZone(zone).toLocalDate();
        List<Frame> made = new ArrayList<>();
        List<Frame> skipped = new ArrayList<>();
        for (Frame f : List.of(new Frame(KEY_D7, eventDay.minusDays(7)), new Frame(KEY_D2, eventDay.minusDays(2)),
                new Frame(KEY_D0, eventDay))) {
            (f.postOn().isBefore(today) ? skipped : made).add(f);
        }
        return new Plan(made, skipped);
    }

    /** "Sun 1 Nov" in the event's zone. */
    public static String dateLine(Instant startsAt, ZoneId zone, String locale) {
        return startsAt.atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM", javaLocale(locale)));
    }

    /** "00:30" in the event's zone. */
    public static String timeLine(Instant startsAt, ZoneId zone) {
        return startsAt.atZone(zone).format(TIME);
    }

    /** "Sun 1 Nov · 00:30". */
    public static String whenLine(Instant startsAt, ZoneId zone, String locale) {
        return dateLine(startsAt, zone, locale) + " · " + timeLine(startsAt, zone);
    }

    /** "Venue, City", whichever parts exist; null when neither does. */
    public static String venueLine(String venueName, String venueCity) {
        String name = venueName == null ? "" : venueName.trim();
        String city = venueCity == null ? "" : venueCity.trim();
        if (name.isEmpty() && city.isEmpty()) return null;
        if (name.isEmpty()) return city;
        if (city.isEmpty()) return name;
        return name + ", " + city;
    }

    /** The big headline on a countdown frame, in the kit locale. */
    public static String headline(String key, Instant startsAt, ZoneId zone, String locale) {
        return switch (key) {
            case KEY_D7 -> pick(locale, Map.of("en", "1 week to go", "fr", "Plus qu'une semaine",
                    "es", "Falta una semana", "uk", "Через тиждень"));
            case KEY_D2 -> pick(locale, Map.of("en", "2 days to go", "fr", "Plus que 2 jours",
                    "es", "Faltan 2 días", "uk", "Через 2 дні"));
            case KEY_D0 -> isEvening(startsAt.atZone(zone).getHour())
                    ? pick(locale, Map.of("en", "Tonight", "fr", "Ce soir", "es", "Esta noche", "uk", "Сьогодні ввечері"))
                    : pick(locale, Map.of("en", "Today", "fr", "Aujourd'hui", "es", "Hoy", "uk", "Сьогодні"));
            default -> throw new IllegalArgumentException("No headline for frame " + key);
        };
    }

    private static boolean isEvening(int localHour) {
        return localHour >= EVENING_HOUR || localHour < NIGHT_END_HOUR;
    }

    /** The "Tickets: link in bio" line. */
    public static String ticketsLine(String locale) {
        return pick(locale, Map.of("en", "Tickets: link in bio", "fr", "Billets : lien en bio",
                "es", "Entradas: enlace en la bio", "uk", "Квитки: посилання в біо"));
    }

    /** Date · time, venue and the tickets line, built from the event and never written by a model. */
    public static String factsLine(Instant startsAt, ZoneId zone, String venueName, String venueCity, String locale) {
        List<String> lines = new ArrayList<>();
        if (startsAt != null) lines.add(whenLine(startsAt, zone, locale));
        String venue = venueLine(venueName, venueCity);
        if (venue != null) lines.add(venue);
        lines.add(ticketsLine(locale));
        return String.join("\n", lines);
    }

    /** Every fixed string that can be drawn on an image in {@code locale}, for the font coverage check. */
    public static List<String> overlayStrings(String locale) {
        List<String> out = new ArrayList<>();
        ZoneId utc = ZoneId.of("UTC");
        for (String key : List.of(KEY_D7, KEY_D2)) out.add(headline(key, Instant.EPOCH, utc, locale));
        out.add(headline(KEY_D0, Instant.parse("1970-01-01T18:00:00Z"), utc, locale));
        out.add(headline(KEY_D0, Instant.parse("1970-01-01T09:00:00Z"), utc, locale));
        DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE d MMM", javaLocale(locale));
        for (int month = 1; month <= 12; month++) {
            for (int d = 1; d <= 7; d++) out.add(ZonedDateTime.of(2026, month, d, 12, 0, 0, 0, utc).format(f));
        }
        return out;
    }

    private static Locale javaLocale(String locale) {
        return Locale.forLanguageTag(supported(locale) ? locale : "en");
    }

    private static String pick(String locale, Map<String, String> byLocale) {
        String s = byLocale.get(locale);
        return s != null ? s : byLocale.get("en");
    }
}
