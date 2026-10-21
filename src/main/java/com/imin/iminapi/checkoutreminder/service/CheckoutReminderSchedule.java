package com.imin.iminapi.checkoutreminder.service;

import com.imin.iminapi.marketing.service.QuietHours;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The reminder's clock rules, pure. Quiet hours are the org's 22:00–09:00 (the same window as
 * {@link QuietHours}); the email date is the event's own zone.
 */
public final class CheckoutReminderSchedule {

    private CheckoutReminderSchedule() {}

    /** {@code startedAt + delay}, moved to the next 09:00 in the org's zone when that falls in quiet hours. */
    public static Instant dueAt(Instant startedAt, String orgTimezone, Duration delay) {
        return outOfQuietHours(startedAt.plus(delay), orgTimezone);
    }

    /** {@code at} itself when the org may be emailed then, else the next 09:00 org-local. */
    public static Instant outOfQuietHours(Instant at, String orgTimezone) {
        ZoneId zone = zone(orgTimezone);
        ZonedDateTime local = at.atZone(zone);
        LocalTime time = local.toLocalTime();
        if (!time.isBefore(QuietHours.EMAIL_QUIET_START)) {
            return local.toLocalDate().plusDays(1).atTime(QuietHours.EMAIL_QUIET_END).atZone(zone).toInstant();
        }
        if (time.isBefore(QuietHours.EMAIL_QUIET_END)) {
            return local.toLocalDate().atTime(QuietHours.EMAIL_QUIET_END).atZone(zone).toInstant();
        }
        return at;
    }

    /** The night starts sooner than {@code minLead} from {@code now}; an undated night has no doors limit. */
    public static boolean tooCloseToDoors(Instant eventStartsAt, Instant now, Duration minLead) {
        return eventStartsAt != null && Duration.between(now, eventStartsAt).compareTo(minLead) < 0;
    }

    /** The checkout started more than {@code maxAge} before {@code now}. */
    public static boolean tooLate(Instant startedAt, Instant now, Duration maxAge) {
        return Duration.between(startedAt, now).compareTo(maxAge) > 0;
    }

    /** "Sun 8 Nov · 22:00" in the event's zone and the buyer's language. */
    public static String eventWhen(Instant startsAt, String eventTimezone, String locale) {
        return DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", javaLocale(locale))
                .withZone(zone(eventTimezone))
                .format(startsAt);
    }

    static Locale javaLocale(String emailLocale) {
        return switch (emailLocale == null ? "en" : emailLocale) {
            case "es" -> Locale.forLanguageTag("es-ES");
            case "fr" -> Locale.FRANCE;
            case "uk" -> Locale.forLanguageTag("uk-UA");
            default -> Locale.ENGLISH;
        };
    }

    static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}
