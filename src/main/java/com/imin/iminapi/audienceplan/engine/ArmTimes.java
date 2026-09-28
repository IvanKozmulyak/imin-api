package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.marketing.service.QuietHours;
import com.imin.iminapi.model.TicketTier;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Send instants of the dated timing arms (logic 8.1), in the event timezone; pure. */
public final class ArmTimes {

    /** Dated arms go out at this local time in the event timezone. */
    public static final LocalTime SEND_TIME = LocalTime.of(18, 0);

    private ArmTimes() {}

    /** Event date − 3 days at 18:00 event time. */
    public static Instant d3(Instant eventStartsAt, ZoneId eventZone) {
        LocalDate eventDate = LocalDate.ofInstant(eventStartsAt, eventZone);
        return eventDate.minusDays(ActionPlanner.D3_DAYS_BEFORE).atTime(SEND_TIME).atZone(eventZone).toInstant();
    }

    /**
     * 18:00 event time on the day the cheapest enabled tier stops selling, only when that day is before the D-3 date
     * and another enabled tier keeps selling after it (no close, or a later one). Ties on price go to the first tier.
     */
    public static Optional<Instant> earlyBirdEnd(List<TicketTier> tiers, Instant eventStartsAt, ZoneId eventZone) {
        List<TicketTier> enabled = tiers.stream().filter(Objects::nonNull).filter(TicketTier::isEnabled).toList();
        Optional<TicketTier> cheapest = enabled.stream().min(Comparator.comparingInt(TicketTier::getPriceMinor));
        if (cheapest.isEmpty() || cheapest.get().getSaleClosesAt() == null) return Optional.empty();
        TicketTier early = cheapest.get();
        Instant closes = early.getSaleClosesAt();
        LocalDate closeDate = LocalDate.ofInstant(closes, eventZone);
        LocalDate d3Date = LocalDate.ofInstant(eventStartsAt, eventZone).minusDays(ActionPlanner.D3_DAYS_BEFORE);
        if (!closeDate.isBefore(d3Date)) return Optional.empty();
        boolean anotherStaysOnSale = enabled.stream()
                .anyMatch(t -> t != early && (t.getSaleClosesAt() == null || t.getSaleClosesAt().isAfter(closes)));
        if (!anotherStaysOnSale) return Optional.empty();
        return Optional.of(closeDate.atTime(SEND_TIME).atZone(eventZone).toInstant());
    }

    /** {@code at}, or the next 09:00 org time when it falls in the 22:00–09:00 email quiet window. */
    public static Instant outOfQuietHours(Instant at, ZoneId orgZone) {
        ZonedDateTime local = at.atZone(orgZone);
        LocalTime time = local.toLocalTime();
        if (!time.isBefore(QuietHours.EMAIL_QUIET_START)) {
            return local.toLocalDate().plusDays(1).atTime(QuietHours.EMAIL_QUIET_END).atZone(orgZone).toInstant();
        }
        if (time.isBefore(QuietHours.EMAIL_QUIET_END)) {
            return local.toLocalDate().atTime(QuietHours.EMAIL_QUIET_END).atZone(orgZone).toInstant();
        }
        return at;
    }
}
