package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.ClassRule;
import com.imin.iminapi.audienceplan.dto.AudiencePlanRecomputeRequest;
import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.audienceplan.engine.ActionPlanner;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.engine.GapCalculator;
import com.imin.iminapi.audienceplan.engine.PlanCalculator;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.NoCapacityException;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Plan;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.PlanSegment;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tier;
import com.imin.iminapi.audienceplan.engine.ResponseModel;
import com.imin.iminapi.audienceplan.model.AudiencePlan;
import com.imin.iminapi.audienceplan.model.AudiencePlanSegment;
import com.imin.iminapi.audienceplan.repository.AudiencePlanRepository;
import com.imin.iminapi.audienceplan.repository.AudiencePlanSegmentRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Reads, reuses or recomputes an event's audience plan. A stored plan is reused while it is under 24 h old and its
 * inputs hash is unchanged; otherwise a new row supersedes it. Locale only picks the stored summary.
 */
@Service
public class PlanService {

    /** Spec: up to 3 segments and 2-3 steps on the plan card. */
    static final int MAX_SEGMENTS = 3;
    static final int MAX_STEPS = 3;
    static final Duration FRESH_FOR = Duration.ofHours(24);
    static final List<String> LOCALES = List.of("en", "es", "fr", "uk");
    static final String DEFAULT_LOCALE = "en";
    /** Supersede-and-retry rounds before reading the current plan unlocked. */
    static final int LOCK_ATTEMPTS = 3;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, Integer>> COUNTS = new TypeReference<>() {};
    private static final TypeReference<List<AudiencePlanResponse.Action>> ACTIONS = new TypeReference<>() {};
    private static final TypeReference<Map<String, AudiencePlanResponse.Summary>> SUMMARIES = new TypeReference<>() {};
    private static final TypeReference<List<AudiencePortraitResponse.NewPeopleGroup>> NEW_PEOPLE = new TypeReference<>() {};

    private final AudiencePlanAccess access;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final CandidateLoader candidates;
    private final AudiencePlanLogic logic;
    private final PlanCalculator calculator;
    private final ResponseModel model;
    private final AudiencePlanRepository plans;
    private final AudiencePlanSegmentRepository segments;
    private final PortraitService portraits;
    private final Clock clock;
    private final DataSource dataSource;
    private volatile Boolean postgres;

    public PlanService(AudiencePlanAccess access, EventRepository events, TicketTierRepository tiers,
                       CandidateLoader candidates, AudiencePlanLogic logic, ResponseModel model,
                       AudiencePlanRepository plans, AudiencePlanSegmentRepository segments,
                       PortraitService portraits, Clock clock, DataSource dataSource) {
        this.access = access;
        this.events = events;
        this.tiers = tiers;
        this.candidates = candidates;
        this.logic = logic;
        this.calculator = new PlanCalculator(logic, model);
        this.model = model;
        this.plans = plans;
        this.segments = segments;
        this.portraits = portraits;
        this.clock = clock;
        this.dataSource = dataSource;
    }

    /** The organizer's plan choices; the defaults are the logic file's target and the mid tickets per order. */
    record Assumptions(int targetPct, double ticketsPerOrder, List<String> excludeSegments) {}

    private record Prepared(PlanCalculator.Input input, String inputsHash, Assumptions assumptions, Instant now,
                            List<AudiencePortraitResponse.NewPeopleGroup> newPeople, int calibrationVersion) {}

    @Transactional
    public AudiencePlanResponse current(UUID orgId, UUID eventId, String locale) {
        access.requireEnabled(orgId);
        String loc = locale(locale);
        Event event = event(orgId, eventId);
        Optional<AudiencePlan> latest = lockLatest(orgId, eventId);
        Assumptions assumptions = latest.map(PlanService::assumptionsOf).orElseGet(this::defaults);
        Prepared prepared = prepare(orgId, event, assumptions);
        if (latest.isPresent() && reusable(latest.get(), prepared.inputsHash(), prepared.now())) {
            return response(latest.get(), loc);
        }
        return response(persist(orgId, event, prepared, latest), loc);
    }

    /** What a background refresh did. */
    public enum Refresh { CREATED, UNCHANGED, SKIPPED }

    /**
     * Background form of {@link #current}: same lock, assumptions and reuse rule, no response. Skips without writing
     * when the event is gone, its org is switched off, it has started, or it cannot be planned (no date, no capacity).
     */
    @Transactional
    public Refresh refresh(UUID eventId) {
        Event event = events.findActive(eventId).orElse(null);
        if (event == null || !access.isEnabled(event.getOrgId())) return Refresh.SKIPPED;
        if (event.getStartsAt() == null || !event.getStartsAt().isAfter(clock.instant())) return Refresh.SKIPPED;
        UUID orgId = event.getOrgId();
        Optional<AudiencePlan> latest = lockLatest(orgId, eventId);
        Assumptions assumptions = latest.map(PlanService::assumptionsOf).orElseGet(this::defaults);
        try {
            Prepared prepared = prepare(orgId, event, assumptions);
            if (latest.isPresent() && reusable(latest.get(), prepared.inputsHash(), prepared.now())) {
                return Refresh.UNCHANGED;
            }
            persist(orgId, event, prepared, latest);
            return Refresh.CREATED;
        } catch (ApiException e) {
            // Thrown before any write (no capacity or no start date): the GET answers 422/409 for the same event.
            return Refresh.SKIPPED;
        }
    }

    /**
     * Whether a GET would reuse {@code plan} as is, without computing a plan: the event's tiers, the org's mailable
     * count and the event's portrait ({@link #newPeople}) are passed in so a list reads each once. Never writes.
     */
    public boolean isFresh(AudiencePlan plan, Event event, List<TicketTier> tierRows, int mailableCount,
                           List<AudiencePortraitResponse.NewPeopleGroup> newPeople) {
        Instant now = clock.instant();
        String hash = inputsHash(event, tierRows, zone(event.getTimezone()), now, mailableCount, assumptionsOf(plan),
                newPeople, model.calibrationVersion());
        return reusable(plan, hash, now);
    }

    private static boolean reusable(AudiencePlan plan, String inputsHash, Instant now) {
        return plan.getInputsHash().equals(inputsHash) && plan.getCreatedAt().isAfter(now.minus(FRESH_FOR));
    }

    /** Always writes a new plan; an omitted override keeps the current plan's value. */
    @Transactional
    public AudiencePlanResponse recompute(UUID orgId, UUID eventId, AudiencePlanRecomputeRequest request, String locale) {
        access.requireEnabled(orgId);
        String loc = locale(locale);
        List<String> excluded = request == null ? null : excludeSegments(request.excludeSegments());
        Event event = event(orgId, eventId);
        Optional<AudiencePlan> latest = lockLatest(orgId, eventId);
        Assumptions base = latest.map(PlanService::assumptionsOf).orElseGet(this::defaults);
        Assumptions assumptions = new Assumptions(
                request != null && request.targetPct() != null ? request.targetPct() : base.targetPct(),
                request != null && request.assumptions() != null && request.assumptions().ticketsPerOrder() != null
                        ? request.assumptions().ticketsPerOrder() : base.ticketsPerOrder(),
                excluded != null ? excluded : base.excludeSegments());
        return response(persist(orgId, event, prepare(orgId, event, assumptions), latest), loc);
    }

    // ── inputs ─────────────────────────────────────────────────────────────

    private Event event(UUID orgId, UUID eventId) {
        // Same 404 as the kill switch, so another org's event and a missing one look alike.
        return events.findActive(eventId).filter(e -> orgId.equals(e.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("Audience plan"));
    }

    private Optional<AudiencePlan> latest(UUID orgId, UUID eventId) {
        return plans.findFirstByOrgIdAndEventIdAndSupersededByIsNullOrderByCreatedAtDesc(orgId, eventId);
    }

    /**
     * Row-locks the current plan so a recompute reads the assumptions a concurrent POST committed. A waiter whose
     * locked row got superseded meanwhile sees no row (Postgres re-checks the WHERE), so it retries on the new one.
     */
    private Optional<AudiencePlan> lockLatest(UUID orgId, UUID eventId) {
        for (int attempt = 0; attempt < LOCK_ATTEMPTS; attempt++) {
            Optional<AudiencePlan> locked = plans.lockCurrent(orgId, eventId).stream()
                    .filter(p -> p.getSupersededBy() == null).findFirst();
            if (locked.isPresent()) return locked;
            if (latest(orgId, eventId).isEmpty()) {
                // No plan row to lock yet: a per-event lock serialises first plans, then a waiter reuses the winner's.
                lockFirstPlan(eventId);
                if (latest(orgId, eventId).isEmpty()) return Optional.empty();
            }
        }
        return latest(orgId, eventId);
    }

    /** Held to commit; serialises the first plan of one event. Public so tests can hold the very same lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockFirstPlan(UUID eventId) {
        if (postgres()) plans.lockEventAdvisory(eventId);
        else plans.lockEventRow(eventId);
    }

    private boolean postgres() {
        Boolean p = postgres;
        if (p == null) {
            try (Connection c = dataSource.getConnection()) {
                p = "PostgreSQL".equals(c.getMetaData().getDatabaseProductName());
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot read the database product", e);
            }
            postgres = p;
        }
        return p;
    }

    private Assumptions defaults() {
        return new Assumptions(logic.logic().targetDefaultPct(), logic.priors().ticketsPerOrder().mid(), List.of());
    }

    private static Assumptions assumptionsOf(AudiencePlan plan) {
        return new Assumptions(plan.getTargetPct(), plan.getTicketsPerOrder(), read(plan.getExcludedSegments(), STRINGS));
    }

    private Prepared prepare(UUID orgId, Event event, Assumptions a) {
        List<TicketTier> tierRows = tiers.findByEventIdOrderBySortOrderAsc(event.getId());
        List<Tier> engineTiers = tierRows.stream().map(t -> new Tier(t.getQuantity(), t.isEnabled())).toList();
        int capacity = PlanCalculator.capacity(engineTiers);
        if (capacity == 0) throw noCapacity();
        if (event.getStartsAt() == null) throw ApiException.invalidState("The event needs a start date before planning");
        int target = PlanCalculator.target(capacity, a.targetPct());
        if (target == 0) throw noCapacity();

        ZoneId zone = zone(event.getTimezone());
        Instant now = clock.instant();
        int calibration = model.calibrationVersion();
        CandidateBuilder.Input loaded = candidates.input(orgId, event, target, a.ticketsPerOrder());
        List<AudiencePortraitResponse.NewPeopleGroup> newPeople = newPeople(event);
        AudiencePortraitResponse.SizeRange regulars = PortraitService.regulars(newPeople);
        GapCalculator.CountRange tribe = regulars == null ? null
                : new GapCalculator.CountRange(regulars.low(), regulars.high());
        PlanCalculator.Input input = new PlanCalculator.Input(orgId, event.getGenreKey(), engineTiers, a.targetPct(),
                a.ticketsPerOrder(), loaded.consentGateExclusions(), loaded.mailable(), now, event.getStartsAt(), zone,
                event.getOnSaleAt(), tribe, Set.copyOf(a.excludeSegments()), MAX_SEGMENTS);

        Set<UUID> mailable = new HashSet<>();
        for (Person p : loaded.mailable()) mailable.add(p.membershipId());
        return new Prepared(input, inputsHash(event, tierRows, zone, now, mailable.size(), a, newPeople, calibration),
                a, now, newPeople, calibration);
    }

    private String inputsHash(Event event, List<TicketTier> tierRows, ZoneId zone, Instant now, int mailableCount,
                              Assumptions a, List<AudiencePortraitResponse.NewPeopleGroup> newPeople,
                              int calibrationVersion) {
        StringBuilder h = new StringBuilder("audience-plan-inputs/1");
        tierRows.stream().sorted(Comparator.comparing(TicketTier::getId))
                .forEach(t -> h.append("|tier:").append(t.getId()).append(':').append(t.getQuantity())
                        .append(':').append(t.isEnabled()));
        h.append("|genre:").append(event.getGenreKey())
                .append("|city:").append(event.getVenueCityKey())
                .append("|start:").append(event.getStartsAt())
                .append("|zone:").append(zone.getId())
                .append("|onSale:").append(event.getOnSaleAt())
                .append("|today:").append(LocalDate.ofInstant(now, zone))
                .append("|mailable:").append(mailableCount)
                .append("|calibration:").append(calibrationVersion)
                .append("|logic:").append(logic.logicVersion())
                .append("|priors:").append(logic.priorsVersion())
                .append("|targetPct:").append(a.targetPct())
                .append("|tpo:").append(a.ticketsPerOrder())
                .append("|exclude:").append(String.join(",", new TreeSet<>(a.excludeSegments())));
        // Only the figures the plan reads: a source's fetch date or stale flag alone must not recompute it.
        for (AudiencePortraitResponse.NewPeopleGroup g : newPeople) {
            h.append("|newPeople:").append(g.key()).append(':').append(String.join(",", g.cityKeys()))
                    .append(':').append(g.size() == null ? "null" : g.size().low() + "-" + g.size().high())
                    .append(':').append(g.method());
        }
        return sha256(h.toString());
    }

    /** The open-data portrait of the event's genre and city; none when the genre is not a bucket or no city is set. */
    List<AudiencePortraitResponse.NewPeopleGroup> newPeople(Event event) {
        String genre = event.getGenreKey();
        String city = event.getVenueCityKey();
        if (genre == null || !logic.genres().whitelist().contains(genre) || city == null || city.isBlank()) {
            return List.of();
        }
        return portraits.forCity(genre, city).groups();
    }

    // ── persistence ────────────────────────────────────────────────────────

    private AudiencePlan persist(UUID orgId, Event event, Prepared prepared, Optional<AudiencePlan> previous) {
        Plan plan;
        try {
            plan = calculator.calculate(prepared.input());
        } catch (NoCapacityException e) {
            throw noCapacity();
        }

        AudiencePlan row = new AudiencePlan();
        row.setId(UUID.randomUUID());
        row.setOrgId(orgId);
        row.setEventId(event.getId());
        row.setMode(key(plan.mode()));
        row.setCapacity(plan.capacity());
        row.setTargetPct(prepared.assumptions().targetPct());
        row.setTargetTickets(plan.targetTickets());
        row.setTicketsPerOrder(prepared.assumptions().ticketsPerOrder());
        row.setExcludedSegments(write(prepared.assumptions().excludeSegments()));
        row.setMailable(plan.mailable());
        if (plan.expected() != null) {
            row.setExpectedLow(plan.expected().low());
            row.setExpectedMid(plan.expected().mid());
            row.setExpectedHigh(plan.expected().high());
        }
        row.setCoverageLow(plan.coverage().low());
        row.setCoverageMid(plan.coverage().mid());
        row.setCoverageHigh(plan.coverage().high());
        row.setVerdict(key(plan.coverage().verdict()));
        row.setGapLow(plan.gap().low());
        row.setGapHigh(plan.gap().high());
        row.setReachNeeded(write(new AudiencePlanResponse.ReachNeeded(reach(plan.reachNeeded().metaAds()),
                reach(plan.reachNeeded().instagramOrganic()))));
        row.setGapExceedsTribe(plan.gapExceedsTribe());
        row.setSmallGroupsNotShown(plan.smallGroupsNotShown());
        row.setOtherGenreInvited(plan.otherGenreInvited());
        row.setOtherGenreHeldBack(plan.otherGenreHeldBack());
        row.setExclusions(write(plan.exclusions()));
        row.setTodayDate(plan.timing().today());
        row.setEventDate(plan.timing().eventDate());
        row.setLaunchDate(plan.timing().launchDate());
        row.setD3Date(plan.timing().d3Date());
        row.setEventStarted(plan.timing().eventStarted());
        row.setActions(write(ActionPlanner.topSteps(plan.actions(), MAX_STEPS).stream().map(PlanService::action).toList()));
        row.setNewPeople(write(prepared.newPeople()));
        row.setLogicVersion(plan.versions().logic());
        row.setPriorsVersion(plan.versions().priors());
        row.setCalibrationVersion(prepared.calibrationVersion());
        row.setInputsHash(prepared.inputsHash());
        // Microseconds, as the database keeps them, so a reused row reads back the same instant.
        row.setCreatedAt(prepared.now().truncatedTo(ChronoUnit.MICROS));
        plans.save(row);

        int position = 0;
        for (PlanSegment s : plan.segments()) {
            AudiencePlanSegment seg = new AudiencePlanSegment();
            seg.setId(UUID.randomUUID());
            seg.setPlanId(row.getId());
            seg.setPosition(position++);
            seg.setClassKey(s.classKey());
            seg.setGenreFit(key(s.fit()));
            seg.setMailable(s.mailable());
            seg.setRateLow(s.rate().low());
            seg.setRateMid(s.rate().mid());
            seg.setRateHigh(s.rate().high());
            seg.setTicketsPerOrder(s.ticketsPerOrder());
            seg.setExpectedLow(s.expected().low());
            seg.setExpectedMid(s.expected().mid());
            seg.setExpectedHigh(s.expected().high());
            seg.setConfidence(key(s.confidence()));
            seg.setReason(write(reason(s, event.getGenreKey())));
            segments.save(seg);
        }

        previous.ifPresent(old -> {
            old.setSupersededBy(row.getId());
            plans.save(old);
        });
        return row;
    }

    private AudiencePlanResponse.SegmentReason reason(PlanSegment s, String eventGenre) {
        // Segments come from the validated logic file's classes, so a miss is a programming error.
        ClassRule rule = logic.logic().classes().stream().filter(c -> c.key().equals(s.classKey())).findFirst()
                .orElseThrow(() -> new IllegalStateException("segment class not in logic file: " + s.classKey()));
        String fit = key(s.fit());
        return new AudiencePlanResponse.SegmentReason(rule.paidOrdersMin(), rule.paidOrdersMax(),
                rule.daysSinceLastPaidMin(), rule.daysSinceLastPaidMax(), rule.daysSinceLastContactMax(),
                rule.requiresImportBasis(), eventGenre, fit);
    }

    private static AudiencePlanResponse.Reach reach(GapCalculator.Reach r) {
        return new AudiencePlanResponse.Reach(key(r.status()), r.low(), r.high());
    }

    private static AudiencePlanResponse.Action action(ActionPlanner.Action a) {
        List<AudiencePlanResponse.ArmDate> arms = a.arms().stream()
                .map(d -> new AudiencePlanResponse.ArmDate(key(d.arm()), d.date())).toList();
        List<String> options = a.type() == ActionPlanner.ActionType.RETHINK_TARGET
                ? ActionPlanner.RETHINK_TARGET_OPTIONS : List.of();
        return new AudiencePlanResponse.Action(key(a.type()), a.classKey(), a.fit() == null ? null : key(a.fit()), arms,
                a.holdoutPct(), options);
    }

    // ── response ───────────────────────────────────────────────────────────

    private AudiencePlanResponse response(AudiencePlan p, String locale) {
        List<AudiencePlanResponse.Segment> segs = new ArrayList<>();
        for (AudiencePlanSegment s : segments.findByPlanIdOrderByPositionAsc(p.getId())) {
            segs.add(new AudiencePlanResponse.Segment(s.getClassKey(), s.getGenreFit(), s.getMailable(),
                    new AudiencePlanResponse.Rate(s.getRateLow(), s.getRateMid(), s.getRateHigh()), s.getTicketsPerOrder(),
                    new AudiencePlanResponse.TicketRange(s.getExpectedLow(), s.getExpectedMid(), s.getExpectedHigh()),
                    s.getConfidence(), read(s.getReason(), AudiencePlanResponse.SegmentReason.class)));
        }
        AudiencePlanResponse.TicketRange expected = p.getExpectedMid() == null ? null
                : new AudiencePlanResponse.TicketRange(p.getExpectedLow(), p.getExpectedMid(), p.getExpectedHigh());
        Map<String, AudiencePlanResponse.Summary> summaries = p.getSummaries() == null ? Map.of()
                : read(p.getSummaries(), SUMMARIES);
        return new AudiencePlanResponse(
                p.getId(), p.getEventId(), p.getMode(), p.getCapacity(), p.getTargetTickets(), p.getMailable(), expected,
                new AudiencePlanResponse.Coverage(p.getCoverageLow(), p.getCoverageMid(), p.getCoverageHigh(), p.getVerdict()),
                new AudiencePlanResponse.Gap(p.getGapLow(), p.getGapHigh()),
                read(p.getReachNeeded(), AudiencePlanResponse.ReachNeeded.class),
                p.getGapExceedsTribe(), List.copyOf(segs), p.getSmallGroupsNotShown(), p.isOtherGenreInvited(),
                p.getOtherGenreHeldBack(), read(p.getExclusions(), COUNTS),
                new AudiencePlanResponse.Timing(p.getTodayDate(), p.getEventDate(), p.getLaunchDate(), p.getD3Date(),
                        (int) ChronoUnit.DAYS.between(p.getTodayDate(), p.getEventDate()), p.isEventStarted()),
                p.getNewPeople() == null ? List.of() : read(p.getNewPeople(), NEW_PEOPLE),
                read(p.getActions(), ACTIONS),
                new AudiencePlanResponse.Assumptions(p.getTargetPct(), p.getTicketsPerOrder(),
                        read(p.getExcludedSegments(), STRINGS)),
                summaries.get(locale),
                new AudiencePlanResponse.Versions(p.getLogicVersion(), p.getPriorsVersion(), p.getModelId()),
                p.getCreatedAt());
    }

    // ── validation ─────────────────────────────────────────────────────────

    static String locale(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_LOCALE;
        String loc = raw.trim().toLowerCase(Locale.ROOT);
        if (!LOCALES.contains(loc)) throw invalid("locale", "must be one of " + String.join(", ", LOCALES));
        return loc;
    }

    /** Null keeps the current value; every element must be a class key of the logic file. */
    private List<String> excludeSegments(List<String> raw) {
        if (raw == null) return null;
        Set<String> classes = new HashSet<>();
        for (ClassRule c : logic.logic().classes()) classes.add(c.key());
        TreeSet<String> out = new TreeSet<>();
        for (String s : raw) {
            if (s == null || !classes.contains(s)) {
                throw invalid("excludeSegments", "unknown class: " + s);
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed", Map.of(field, message));
    }

    private static ApiException noCapacity() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.AUDIENCE_PLAN_NO_CAPACITY,
                "The event has no enabled ticket capacity to plan against");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** {@code Event.timezone} defaults to UTC; an unreadable zone falls back to it too. */
    static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write audience plan JSON", e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read audience plan JSON", e);
        }
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read audience plan JSON", e);
        }
    }
}
