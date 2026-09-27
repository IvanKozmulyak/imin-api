package com.imin.iminapi.audienceplan.sim;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.GenreFit;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Genres;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.audienceplan.engine.PlanCalculator;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A synthetic world of organizers, events, fans and purchases with known truth, for evaluating the plan engine.
 * Behaviour is drawn from the priors file with a fixed seed, so the same seed always gives the same world.
 */
public final class SyntheticDataGenerator {

    public static final long SEED = 20260926L;
    public static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    public static final int HISTORY_DAYS = 730;
    public static final int DAYS_TO_TARGET = 28;

    /** Organizer-named checkout text version (on the logic allowlist), and the import-row source. */
    static final String CHECKOUT_TEXT_VERSION = "checkout-org-named-2026-09";
    static final String SOURCE_CHECKOUT = "checkout";
    static final String SOURCE_IMPORT_ROW = "organizer_import_row";

    static final String LOYAL = "loyal";
    static final String REPEAT = "repeat";
    static final String FIRST_TIMER = "first_timer";
    static final String LAPSING = "lapsing";
    static final String DORMANT = "dormant";
    static final String IMPORTED = "imported";
    static final String NONE = "none";

    // Behaviour knobs that the priors file does not carry; they shape history, never the response truth.
    private static final double MAILABLE_SHARE = 0.6;
    private static final double IMPORTED_SHARE = 0.08;
    private static final double FREE_EVENT_SHARE = 0.05;
    private static final double REFUND_SHARE = 0.03;
    private static final double MEAN_ACTIVE_DAYS = 360;
    private static final double BUY_MULT_ADJACENT = 0.35;
    private static final double BUY_MULT_OTHER = 0.08;

    /** One fake organizer; {@code cadenceDays} is the gap between its events. */
    public record OrgSpec(String key, String cityKey, ZoneId zone, String homeGenre, int fans, int cadenceDays,
                          int capacity) {}

    /** Five fake organizers; names and places are descriptive only. */
    public static final List<OrgSpec> ORGS = List.of(
            new OrgSpec("techno-metz", "metz", ZoneId.of("Europe/Paris"), "house & techno", 900, 14, 300),
            new OrgSpec("afro-paris", "paris", ZoneId.of("Europe/Paris"), "latin & afrobeats", 1400, 10, 500),
            new OrgSpec("latin-lyon", "lyon", ZoneId.of("Europe/Paris"), "latin & afrobeats", 700, 14, 250),
            new OrgSpec("party-strasbourg", "strasbourg", ZoneId.of("Europe/Paris"), "club / open format", 500, 21, 400),
            new OrgSpec("coffee-rave-lisbon", "lisbon", ZoneId.of("Europe/Lisbon"), "house & techno", 350, 28, 150));

    /** What the generator knows about one fan and the engine must infer. */
    public record FanTruth(UUID membershipId, String favouriteGenre, String trueClass, int noShowN, boolean mailable,
                           boolean imported, int paidOrders) {}

    public record Fan(FanTruth truth, List<Order> orders, List<Ticket> tickets, List<ConsentRecord> consents) {}

    /** Per-org true behaviour, each drawn once from its prior band. */
    public record OrgBehaviour(Map<String, Double> classRate, double noShowMultiplier, double ticketsPerOrder,
                               double showUp) {}

    /** A fan's simulated response to an invitation to one target event. */
    public record Outcome(Fit trueFit, double rate, boolean bought, int tickets) {}

    public record Target(Event event, List<PlanCalculator.Tier> tiers, Map<UUID, Outcome> outcomes) {}

    public record SimOrg(OrgSpec spec, UUID orgId, OrgBehaviour behaviour, List<Event> events, List<Fan> fans,
                         List<Target> targets) {}

    public record World(long seed, Instant now, List<SimOrg> orgs) {}

    private final AudiencePlanLogic logic;

    public SyntheticDataGenerator(AudiencePlanLogic logic) {
        this.logic = Objects.requireNonNull(logic);
    }

    public World generate(long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        List<SimOrg> orgs = new ArrayList<>();
        for (OrgSpec spec : ORGS) orgs.add(org(spec, rnd.split()));
        return new World(seed, NOW, List.copyOf(orgs));
    }

    private SimOrg org(OrgSpec spec, SplittableRandom rnd) {
        UUID orgId = uuid(rnd);
        OrgBehaviour behaviour = behaviour(rnd);
        LocalDate day0 = NOW.atZone(spec.zone()).toLocalDate().minusDays(HISTORY_DAYS);

        List<Event> events = new ArrayList<>();
        Set<UUID> freeEvents = new HashSet<>();
        List<Integer> eventDays = new ArrayList<>();
        for (int d = rnd.nextInt(spec.cadenceDays()); d < HISTORY_DAYS; d += spec.cadenceDays()) {
            Event e = event(orgId, spec, eventGenre(spec.homeGenre(), rnd), day0.plusDays(d));
            events.add(e);
            eventDays.add(d);
            if (rnd.nextDouble() < FREE_EVENT_SHARE) freeEvents.add(e.getId());
        }

        List<Fan> fans = new ArrayList<>();
        int lastJoin = HISTORY_DAYS - spec.cadenceDays();
        for (int i = 0; i < spec.fans(); i++) {
            fans.add(buyer(orgId, spec, behaviour, events, eventDays, freeEvents, lastJoin, rnd));
        }
        int imported = (int) Math.round(spec.fans() * IMPORTED_SHARE);
        for (int i = 0; i < imported; i++) fans.add(importedContact(spec, day0, rnd));

        List<Target> targets = new ArrayList<>();
        for (String genre : targetGenres(spec.homeGenre())) {
            Event e = event(orgId, spec, genre, NOW.atZone(spec.zone()).toLocalDate().plusDays(DAYS_TO_TARGET));
            targets.add(new Target(e, List.of(new PlanCalculator.Tier(spec.capacity(), true)),
                    outcomes(fans, genre, behaviour, rnd)));
        }
        return new SimOrg(spec, orgId, behaviour, List.copyOf(events), List.copyOf(fans), List.copyOf(targets));
    }

    private OrgBehaviour behaviour(SplittableRandom rnd) {
        Map<String, Double> rates = new LinkedHashMap<>();
        for (Map.Entry<String, AudiencePlanLogic.ClassPrior> c : new TreeMap<>(logic.priors().classes()).entrySet()) {
            rates.put(c.getKey(), triangular(c.getValue().purchaseRate(), rnd));
        }
        return new OrgBehaviour(Map.copyOf(rates), triangular(logic.priors().noShowBefore(), rnd),
                triangular(logic.priors().ticketsPerOrder(), rnd), triangular(logic.priors().showUpPaid(), rnd));
    }

    /** 70% home genre, 20% one of its neighbours, 10% an unrelated bucket. */
    private String eventGenre(String home, SplittableRandom rnd) {
        double u = rnd.nextDouble();
        if (u < 0.70) return home;
        List<String> adjacent = adjacent(home);
        if (u < 0.90 && !adjacent.isEmpty()) return adjacent.get(rnd.nextInt(adjacent.size()));
        List<String> other = unrelated(home);
        return other.get(rnd.nextInt(other.size()));
    }

    /** A fan's favourite: 65% the org's home genre, 20% a neighbour, 15% any other bucket. */
    private String favourite(String home, SplittableRandom rnd) {
        double u = rnd.nextDouble();
        if (u < 0.65) return home;
        List<String> adjacent = adjacent(home);
        if (u < 0.85 && !adjacent.isEmpty()) return adjacent.get(rnd.nextInt(adjacent.size()));
        List<String> other = unrelated(home);
        return other.get(rnd.nextInt(other.size()));
    }

    /** Home genre, its first neighbour, and the first unrelated bucket of the whitelist. */
    private List<String> targetGenres(String home) {
        return List.of(home, adjacent(home).get(0), unrelated(home).get(0));
    }

    private Fan buyer(UUID orgId, OrgSpec spec, OrgBehaviour behaviour, List<Event> events, List<Integer> eventDays,
                      Set<UUID> freeEvents, int lastJoin, SplittableRandom rnd) {
        UUID membershipId = uuid(rnd);
        String favourite = favourite(spec.homeGenre(), rnd);
        int join = rnd.nextInt(lastJoin);
        double activeDays = -MEAN_ACTIVE_DAYS * Math.log(1 - rnd.nextDouble());
        double intensity = triangular(new Band(0.03, 0.10, 0.40), rnd);
        boolean mailable = rnd.nextDouble() < MAILABLE_SHARE;

        List<Order> orders = new ArrayList<>();
        List<Ticket> tickets = new ArrayList<>();
        List<ConsentRecord> consents = new ArrayList<>();
        boolean first = true;
        int paidOrders = 0;
        Instant lastPaid = null;
        Instant firstPaid = null;
        Set<UUID> noShowEvents = new HashSet<>();
        for (int i = 0; i < events.size(); i++) {
            int day = eventDays.get(i);
            if (day < join) continue;
            if (!first && day > join + activeDays) break;
            Event e = events.get(i);
            // Everyone joins by buying for the first event after their join day.
            if (!first && rnd.nextDouble() >= intensity * buyMultiplier(favourite, e.getGenreKey())) continue;
            first = false;

            boolean free = freeEvents.contains(e.getId());
            boolean refunded = !free && rnd.nextDouble() < REFUND_SHARE;
            int count = ticketsPerOrder(behaviour.ticketsPerOrder(), rnd);
            Instant createdAt = e.getStartsAt().minus(Duration.ofMinutes(60 + rnd.nextInt(10 * 24 * 60)));
            Order o = order(orgId, e, createdAt, free, count, rnd);
            orders.add(o);
            double showUp = free ? triangular(logic.priors().showUpFreeRsvp(), rnd) : behaviour.showUp();
            boolean scanned = !refunded && rnd.nextDouble() < showUp;
            for (int t = 0; t < count; t++) {
                Ticket ticket = new Ticket();
                ticket.setId(uuid(rnd));
                ticket.setOrderId(o.getId());
                ticket.setEventId(e.getId());
                ticket.setState(refunded ? Ticket.STATE_REFUNDED : scanned ? Ticket.STATE_REDEEMED : Ticket.STATE_ISSUED);
                tickets.add(ticket);
            }
            if (!free && !refunded) {
                paidOrders++;
                if (firstPaid == null) firstPaid = createdAt;
                lastPaid = createdAt;
                if (!scanned) noShowEvents.add(e.getId());
            }
        }
        if (mailable && firstPaid != null) {
            consents.add(consent(membershipId, SOURCE_CHECKOUT, CHECKOUT_TEXT_VERSION, firstPaid, rnd));
        }
        FanTruth truth = new FanTruth(membershipId, favourite,
                trueClass(paidOrders, lastPaid, spec.zone(), false), noShowEvents.size(),
                mailable, false, paidOrders);
        return new Fan(truth, List.copyOf(orders), List.copyOf(tickets), List.copyOf(consents));
    }

    private Fan importedContact(OrgSpec spec, LocalDate day0, SplittableRandom rnd) {
        UUID membershipId = uuid(rnd);
        String favourite = favourite(spec.homeGenre(), rnd);
        Instant at = day0.plusDays(rnd.nextInt(HISTORY_DAYS)).atTime(LocalTime.NOON).atZone(spec.zone()).toInstant();
        ConsentRecord c = consent(membershipId, SOURCE_IMPORT_ROW, null, at, rnd);
        FanTruth truth = new FanTruth(membershipId, favourite, trueClass(0, null, spec.zone(), true), 0, true, true, 0);
        return new Fan(truth, List.of(), List.of(), List.of(c));
    }

    /**
     * Each classed fan's response to an invitation: the org's true class rate × genre-fit modifier of the true
     * favourite × the org's no-show multiplier when the fan has missed an event.
     */
    private Map<UUID, Outcome> outcomes(List<Fan> fans, String targetGenre, OrgBehaviour behaviour,
                                        SplittableRandom rnd) {
        Map<UUID, Outcome> out = new LinkedHashMap<>();
        GenreFit modifiers = logic.priors().genreFit();
        for (Fan fan : fans) {
            FanTruth t = fan.truth();
            // ponytail: a fan with no class has no prior to draw from, so the simulator never has them buy.
            if (NONE.equals(t.trueClass())) continue;
            Fit fit = t.imported() ? Fit.UNKNOWN : fit(t.favouriteGenre(), targetGenre);
            double modifier = switch (fit) {
                case SAME -> modifiers.same();
                case ADJACENT -> modifiers.adjacent();
                case OTHER -> modifiers.other();
                case UNKNOWN -> modifiers.unknown();
            };
            double rate = behaviour.classRate().get(t.trueClass()) * modifier
                    * (t.noShowN() > 0 ? behaviour.noShowMultiplier() : 1.0);
            boolean bought = rnd.nextDouble() < rate;
            int tickets = ticketsPerOrder(behaviour.ticketsPerOrder(), rnd);
            out.put(t.membershipId(), new Outcome(fit, rate, bought, bought ? tickets : 0));
        }
        return Map.copyOf(out);
    }

    /** The spec's class rules applied to the generator's own schedule (paid orders only). */
    static String trueClass(int paidOrders, Instant lastPaid, ZoneId zone, boolean imported) {
        if (paidOrders == 0) return imported ? IMPORTED : NONE;
        long days = ChronoUnit.DAYS.between(lastPaid.atZone(zone).toLocalDate(), NOW.atZone(zone).toLocalDate());
        if (days <= 90) return paidOrders >= 3 ? LOYAL : paidOrders == 2 ? REPEAT : FIRST_TIMER;
        if (days <= 180) return LAPSING;
        // Two years of history keep every last contact inside the 1095-day window.
        return DORMANT;
    }

    Fit fit(String favourite, String eventGenre) {
        if (favourite.equals(eventGenre)) return Fit.SAME;
        return logic.genres().adjacency().getOrDefault(eventGenre, Set.of()).contains(favourite) ? Fit.ADJACENT : Fit.OTHER;
    }

    private double buyMultiplier(String favourite, String eventGenre) {
        return switch (fit(favourite, eventGenre)) {
            case SAME, UNKNOWN -> 1.0;
            case ADJACENT -> BUY_MULT_ADJACENT;
            case OTHER -> BUY_MULT_OTHER;
        };
    }

    private List<String> adjacent(String genre) {
        return logic.genres().adjacency().getOrDefault(genre, Set.of()).stream().sorted().toList();
    }

    private List<String> unrelated(String genre) {
        Genres g = logic.genres();
        Set<String> near = g.adjacency().getOrDefault(genre, Set.of());
        return g.whitelist().stream().filter(k -> !k.equals(genre) && !near.contains(k)).toList();
    }

    /** Whole tickets with mean {@code m} in [1, 3]: 1 + Binomial(2, (m − 1) / 2). */
    static int ticketsPerOrder(double m, SplittableRandom rnd) {
        double p = Math.max(0, Math.min(1, (m - 1) / 2));
        return 1 + (rnd.nextDouble() < p ? 1 : 0) + (rnd.nextDouble() < p ? 1 : 0);
    }

    /** Triangular draw with the band's low, mid (mode) and high. */
    static double triangular(Band b, SplittableRandom rnd) {
        double a = b.low(), c = b.mid(), h = b.high();
        if (h <= a) return a;
        double u = rnd.nextDouble();
        double f = (c - a) / (h - a);
        return u < f ? a + Math.sqrt(u * (h - a) * (c - a)) : h - Math.sqrt((1 - u) * (h - a) * (h - c));
    }

    private Event event(UUID orgId, OrgSpec spec, String genre, LocalDate date) {
        Event e = new Event();
        e.setId(uuidFrom(orgId, date, genre));
        e.setOrgId(orgId);
        e.setGenreKey(genre);
        e.setVenueCityKey(spec.cityKey());
        e.setType("Club");
        e.setTimezone(spec.zone().getId());
        Instant start = date.atTime(22, 0).atZone(spec.zone()).toInstant();
        e.setStartsAt(start);
        e.setEndsAt(start.plus(Duration.ofHours(6)));
        return e;
    }

    private static Order order(UUID orgId, Event e, Instant createdAt, boolean free, int count, SplittableRandom rnd) {
        Order o = new Order();
        o.setId(uuid(rnd));
        o.setOrgId(orgId);
        o.setEventId(e.getId());
        o.setPaymentMethod(free ? "free" : "stripe");
        o.setTotalMinor(free ? 0 : 2_000L * count);
        o.setCurrency("EUR");
        o.setCreatedAt(createdAt);
        return o;
    }

    private static ConsentRecord consent(UUID membershipId, String source, String textVersion, Instant at,
                                         SplittableRandom rnd) {
        ConsentRecord c = new ConsentRecord();
        c.setId(uuid(rnd));
        c.setMembershipId(membershipId);
        c.setStatus("subscribed");
        c.setLawfulBasis("explicit");
        c.setSource(source);
        c.setTextVersion(textVersion);
        c.setOccurredAt(at);
        return c;
    }

    private static UUID uuid(SplittableRandom rnd) {
        return new UUID(rnd.nextLong(), rnd.nextLong());
    }

    /** Stable event id so two events of one org never collide and the world stays seed-determined. */
    private static UUID uuidFrom(UUID orgId, LocalDate date, String genre) {
        return UUID.nameUUIDFromBytes((orgId + "|" + date + "|" + genre).getBytes(StandardCharsets.UTF_8));
    }

    /** The shipped logic, priors and genres files. */
    public static AudiencePlanLogic shippedLogic() {
        ClassLoader cl = SyntheticDataGenerator.class.getClassLoader();
        try (InputStream logic = cl.getResourceAsStream("audienceplan/logic-v1.yaml");
             InputStream priors = cl.getResourceAsStream("audienceplan/priors-v1.yaml");
             InputStream genres = cl.getResourceAsStream("audienceplan/genres-v1.yaml")) {
            return LogicLoader.parse(logic, priors, genres);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fans of an org keyed by membership id, in generation order. */
    public static Map<UUID, Fan> byId(SimOrg org) {
        Map<UUID, Fan> out = new LinkedHashMap<>();
        for (Fan f : org.fans()) out.put(f.truth().membershipId(), f);
        return out;
    }

    /** Class counts across the world, for reporting and shape checks. */
    public static Map<String, Integer> classCounts(World world) {
        Map<String, Integer> out = new HashMap<>();
        for (SimOrg o : world.orgs()) for (Fan f : o.fans()) out.merge(f.truth().trueClass(), 1, Integer::sum);
        return out;
    }
}
