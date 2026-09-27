package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Computes one membership's {@code fan_features} values from its purchases and consent history.
 * Pure: reads only its inputs, never membership columns typed by the organizer or email opens/clicks.
 */
public final class FanFeatureCalculator {

    /** Consent sources the person creates themselves; only these count as contact from the person. */
    static final Set<String> PERSON_CONSENT_SOURCES =
            Set.of("checkout", "door_qr", "survey", "preference_centre_row", "order_confirmation");

    /** Source of the retention job's unsubscribe; profiling stays empty until the person makes contact again. */
    public static final String RETENTION_SOURCE = "retention_3y";

    /** Closed format keys, from the organizer wizard's event types; any other type is dropped. */
    static final Set<String> FORMAT_WHITELIST = Set.of("festival", "rave", "club", "concert", "open_air");

    private final AudiencePlanLogic logic;
    private final Set<String> genreWhitelist;

    public FanFeatureCalculator(AudiencePlanLogic logic) {
        this.logic = Objects.requireNonNull(logic);
        this.genreWhitelist = Set.copyOf(logic.genres().whitelist());
    }

    /**
     * @param orders             the buyer's orders; any with another org id are ignored
     * @param tickets            tickets of those orders
     * @param events             the events of those orders; any with another org id are ignored
     * @param consents           the membership's consent records
     * @param surveyResponseAts  times the person answered a survey
     */
    public record Input(UUID orgId, ZoneId orgZone, boolean objectedProfiling,
                        Collection<Order> orders, Collection<Ticket> tickets, Collection<Event> events,
                        Collection<ConsentRecord> consents, Collection<Instant> surveyResponseAts) {}

    /** {@code taste}, {@code cities} and {@code formats} are empty (not null) when nothing qualifies, the person objected or retention cleared them; {@code avgGroupSize} is null with no paid order. */
    public record Result(int paidOrders, Instant firstPaidPurchaseAt, Instant lastPaidPurchaseAt,
                         Integer daysSinceLastPaid, String fanClass, Map<String, Double> taste,
                         List<String> cities, List<String> formats, int noShowN, BigDecimal avgGroupSize,
                         Instant lastContactFromPersonAt, int logicVersion) {}

    public Result calculate(Input in, Clock clock) {
        Instant asOf = clock.instant();
        Map<UUID, List<Ticket>> ticketsByOrder = in.tickets().stream()
                .collect(Collectors.groupingBy(Ticket::getOrderId));
        Map<UUID, Event> eventsById = in.events().stream()
                .filter(e -> in.orgId().equals(e.getOrgId()))
                .collect(Collectors.toMap(Event::getId, Function.identity(), (a, b) -> a));

        List<Order> paid = in.orders().stream()
                .filter(o -> in.orgId().equals(o.getOrgId()))
                .filter(o -> PaidOrderRules.isPaid(o, ticketsByOrder.getOrDefault(o.getId(), List.of())))
                .toList();

        Instant firstPaid = paid.stream().map(Order::getCreatedAt).min(Instant::compareTo).orElse(null);
        Instant lastPaid = paid.stream().map(Order::getCreatedAt).max(Instant::compareTo).orElse(null);
        Instant lastContact = lastContactFromPerson(lastPaid, in.consents(), in.surveyResponseAts());

        Integer daysSinceLastPaid = wholeDays(lastPaid, asOf, in.orgZone());
        Integer daysSinceLastContact = wholeDays(lastContact, asOf, in.orgZone());
        String fanClass = ClassRules.classify(logic.logic().classes(), paid.size(), daysSinceLastPaid,
                daysSinceLastContact, ClassRules.importBasisValid(in.consents()));

        // One entry per distinct bought event of this org, in order of first purchase.
        Map<UUID, Event> boughtEvents = new LinkedHashMap<>();
        Map<UUID, Instant> firstOrderAtByEvent = new LinkedHashMap<>();
        for (Order o : paid) {
            Event e = eventsById.get(o.getEventId());
            if (e == null) continue;
            boughtEvents.putIfAbsent(e.getId(), e);
            firstOrderAtByEvent.merge(o.getEventId(), o.getCreatedAt(), (a, b) -> a.isBefore(b) ? a : b);
        }

        boolean profile = !in.objectedProfiling() && !retentionCleared(in.consents(), lastContact);
        Map<String, Double> taste = !profile ? Map.of() : TasteCalculator.taste(
                boughtEvents.values().stream()
                        .map(e -> new TasteCalculator.Purchase(e.getGenreKey(),
                                e.getStartsAt() != null ? e.getStartsAt() : firstOrderAtByEvent.get(e.getId())))
                        .toList(),
                genreWhitelist, logic.logic().tasteHalfLifeDays(), asOf);

        return new Result(paid.size(), firstPaid, lastPaid, daysSinceLastPaid, fanClass, taste,
                profile ? cities(boughtEvents.values()) : List.of(),
                profile ? formats(boughtEvents.values()) : List.of(),
                noShows(paid, ticketsByOrder, eventsById, asOf), avgGroupSize(paid, ticketsByOrder),
                lastContact, logic.logicVersion());
    }

    /** Latest of the last paid purchase, an explicit consent the person gave themselves, and a survey answer. */
    static Instant lastContactFromPerson(Instant lastPaid, Collection<ConsentRecord> consents,
                                         Collection<Instant> surveyResponseAts) {
        Instant latest = lastPaid;
        for (ConsentRecord c : consents) {
            if (!ClassRules.STATUS_SUBSCRIBED.equals(c.getStatus())) continue;
            if (!ClassRules.BASIS_EXPLICIT.equals(c.getLawfulBasis())) continue;
            if (!PERSON_CONSENT_SOURCES.contains(c.getSource())) continue;
            // Anyone can type an address at the door; only a confirmed one is contact from the person.
            if (c.isAwaitingConfirmation()) continue;
            latest = later(latest, c.getOccurredAt());
        }
        for (Instant s : surveyResponseAts) latest = later(latest, s);
        return latest;
    }

    /** True when the latest email retention unsubscribe is not followed by contact from the person. */
    static boolean retentionCleared(Collection<ConsentRecord> consents, Instant lastContact) {
        Instant cleared = null;
        for (ConsentRecord c : consents) {
            if (!RETENTION_SOURCE.equals(c.getSource()) || !ClassRules.CHANNEL_EMAIL.equals(c.getChannel())) continue;
            cleared = later(cleared, c.getOccurredAt());
        }
        return cleared != null && (lastContact == null || !lastContact.isAfter(cleared));
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    /** Whole calendar days from {@code from} to {@code asOf}, both taken as dates in the org timezone. */
    static Integer wholeDays(Instant from, Instant asOf, ZoneId zone) {
        if (from == null) return null;
        LocalDate fromDate = from.atZone(zone).toLocalDate();
        LocalDate asOfDate = asOf.atZone(zone).toLocalDate();
        return (int) ChronoUnit.DAYS.between(fromDate, asOfDate);
    }

    private static List<String> cities(Collection<Event> events) {
        Set<String> out = new TreeSet<>();
        for (Event e : events) {
            if (e.getVenueCityKey() != null && !e.getVenueCityKey().isBlank()) out.add(e.getVenueCityKey());
        }
        return List.copyOf(out);
    }

    /** Event types are organizer free text in storage; only the wizard's closed list is kept. */
    private static List<String> formats(Collection<Event> events) {
        Set<String> out = new TreeSet<>();
        for (Event e : events) {
            if (e.getType() == null) continue;
            String key = e.getType().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
            if (FORMAT_WHITELIST.contains(key)) out.add(key);
        }
        return List.copyOf(out);
    }

    /** Ended events bought for where no ticket was scanned; many unscanned tickets to one event are one miss. */
    private static int noShows(List<Order> paid, Map<UUID, List<Ticket>> ticketsByOrder,
                               Map<UUID, Event> eventsById, Instant asOf) {
        Set<UUID> attended = new HashSet<>();
        Set<UUID> unscanned = new HashSet<>();
        for (Order o : paid) {
            for (Ticket t : ticketsByOrder.getOrDefault(o.getId(), List.of())) {
                if (Ticket.STATE_REDEEMED.equals(t.getState())) attended.add(o.getEventId());
                else if (PaidOrderRules.countable(t)) unscanned.add(o.getEventId());
            }
        }
        unscanned.removeAll(attended);
        int n = 0;
        for (UUID eventId : unscanned) {
            Event e = eventsById.get(eventId);
            if (e == null) continue;
            Instant end = e.getEndsAt() != null ? e.getEndsAt() : e.getStartsAt();
            if (end != null && end.isBefore(asOf)) n++;
        }
        return n;
    }

    private static BigDecimal avgGroupSize(List<Order> paid, Map<UUID, List<Ticket>> ticketsByOrder) {
        if (paid.isEmpty()) return null;
        long tickets = 0;
        for (Order o : paid) {
            tickets += ticketsByOrder.getOrDefault(o.getId(), List.of()).stream()
                    .filter(PaidOrderRules::countable).count();
        }
        return BigDecimal.valueOf(tickets).divide(BigDecimal.valueOf(paid.size()), 3, RoundingMode.HALF_UP);
    }
}
