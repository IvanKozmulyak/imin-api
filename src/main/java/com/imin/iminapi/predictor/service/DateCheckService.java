package com.imin.iminapi.predictor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.predictor.config.DateCheckAccess;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.dto.ActionDto;
import com.imin.iminapi.predictor.dto.AssumptionDto;
import com.imin.iminapi.predictor.dto.AssumptionsPatch;
import com.imin.iminapi.predictor.dto.DateCheckConfigResponse;
import com.imin.iminapi.predictor.dto.DateCheckDateDto;
import com.imin.iminapi.predictor.dto.DateCheckDateDto.BreakdownLine;
import com.imin.iminapi.predictor.dto.DateCheckDateDto.NotChecked;
import com.imin.iminapi.predictor.dto.DateCheckRequest;
import com.imin.iminapi.predictor.dto.DateCheckResponse;
import com.imin.iminapi.predictor.dto.DateCheckSummaryDto;
import com.imin.iminapi.predictor.dto.EventDateCheckDto;
import com.imin.iminapi.predictor.dto.FindingDto;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.model.DateCheckFinding;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckFindingRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.rules.ActionItem;
import com.imin.iminapi.predictor.rules.ActionPicker;
import com.imin.iminapi.predictor.rules.Assumption;
import com.imin.iminapi.predictor.rules.AssumptionResolver;
import com.imin.iminapi.predictor.rules.DateCheckInput;
import com.imin.iminapi.predictor.rules.DateCheckInput.KnownEvent;
import com.imin.iminapi.predictor.rules.DateResult;
import com.imin.iminapi.predictor.rules.Finding;
import com.imin.iminapi.predictor.rules.NightDates;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBank.GenreProfile;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.rules.Ranker;
import com.imin.iminapi.predictor.rules.RuleEngine;
import com.imin.iminapi.predictor.rules.Scorer;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.util.CountryTimeZones;
import com.imin.iminapi.util.Times;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Check a date": runs the rule engine on each candidate date, scores, ranks and stores the result, and
 * writes the DATE_CHECK ledger row in the same transaction before answering. Synchronous; no LLM, no quota.
 *
 * <p>Event link: an event created from a check stores it in {@code events.date_check_id}, and a check made with
 * {@code eventId} stores the event. The most recently scored of the two is the event's current check.
 *
 * <p>Radar: {@link #radarRerun} re-runs an event's current check for its night at a days-out milestone as a
 * new {@code origin='radar'} row, which then becomes the current check. The org's list shows organizer runs only.
 */
@Service
public class DateCheckService {

    static final String STATUS_DONE = "done";
    /** ponytail: research is not built yet, so every check answers "off" and nothing is queued. */
    static final String RESEARCH_OFF = "off";
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private static final TypeReference<List<Assumption>> ASSUMPTIONS = new TypeReference<>() {};
    private static final TypeReference<List<ActionDto>> ACTIONS = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, Object>> FACTS = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<Integer>> INTS = new TypeReference<>() {};
    private static final TypeReference<List<KnownEvent>> KNOWN = new TypeReference<>() {};

    /** The organizer's own answers kept in {@code assumptions_json}; null = not given. */
    private record Answers(List<Integer> audienceAge, List<String> communities, Integer buyingLeadDays) {}

    private final DateCheckAccess access;
    private final RateLimiter rateLimiter;
    private final DateCheckValidator validator;
    private final DateCheckProperties props;
    private final QuestionBank bank;
    private final RuleEngine engine;
    private final DateCheckRepository checks;
    private final DateCheckDateRepository dates;
    private final DateCheckFindingRepository findings;
    private final PredictionLedgerService ledger;
    private final OrganizationRepository orgs;
    private final EventRepository events;
    private final Clock clock;
    private final EntityManager entityManager;

    public DateCheckService(DateCheckAccess access, RateLimiter rateLimiter, DateCheckValidator validator,
                            DateCheckProperties props, QuestionBank bank, RuleEngine engine, DateCheckRepository checks,
                            DateCheckDateRepository dates, DateCheckFindingRepository findings,
                            PredictionLedgerService ledger, OrganizationRepository orgs, EventRepository events,
                            Clock clock, EntityManager entityManager) {
        this.access = access;
        this.rateLimiter = rateLimiter;
        this.validator = validator;
        this.props = props;
        this.bank = bank;
        this.engine = engine;
        this.checks = checks;
        this.dates = dates;
        this.findings = findings;
        this.ledger = ledger;
        this.orgs = orgs;
        this.events = events;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Transactional
    public DateCheckResponse create(AuthPrincipal p, DateCheckRequest req) {
        access.requireEnabled(p.orgId());
        rateLimiter.consume("predictor-date-check", p.actorLabel());
        String orgCountry = orgs.findById(p.orgId()).map(Organization::getCountry).orElse(null);
        DateCheckValidator.Resolved r = validator.validate(req, orgCountry);
        if (req.eventId() != null) {
            events.findById(req.eventId())
                    .filter(e -> p.orgId().equals(e.getOrgId()) && e.getDeletedAt() == null)
                    .orElseThrow(() -> ApiException.notFound("Event"));
        }

        DateCheck c = new DateCheck();
        c.setOrgId(p.orgId());
        c.setCreatedBy(p.userId());
        c.setCity(req.city().trim());
        c.setCountry(r.country());
        c.setPostalCode(trimToNull(req.postalCode()));
        c.setEventId(req.eventId());
        c.setGenreFamily(req.genreFamily());
        c.setSubGenre(trimToNull(req.subGenre()));
        c.setCapacity(req.capacity());
        c.setPriceMinor(req.priceMinor());
        c.setFormat(trimToNull(req.format()));
        c.setStartHour(req.startHour() == null ? null : req.startHour().shortValue());
        c.setEndHour(req.endHour() == null ? null : req.endHour().shortValue());
        List<String> lineup = req.lineup() == null ? null
                : req.lineup().stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();
        c.setLineupJson(lineup == null ? null : write(lineup));
        c.setKnownEventsJson(req.knownEvents() == null ? null : write(req.knownEvents().stream()
                .map(k -> new KnownEvent(k.name().trim(), k.date(), trimToNull(k.venue()), k.strength()))
                .toList()));
        c.setResearch(false);
        c.setStatus(STATUS_DONE);

        List<LocalDate> candidates = req.dates().stream().sorted().toList();
        Answers answers = new Answers(req.audienceAge(), upper(req.communities()), req.buyingLeadDays());
        DateCheckInput in = input(c, r.today(), answers);
        // Assumptions are set before the first save so the stored row is never updated in this transaction.
        c.setAssumptionsJson(write(AssumptionResolver.resolve(in, profile(c))));
        c.setQuestionBankVersion(bank.version());
        checks.save(c);
        run(c, in, candidates);
        return toResponse(c);
    }

    @Transactional(readOnly = true)
    public DateCheckResponse get(AuthPrincipal p, UUID id) {
        access.requireEnabled(p.orgId());
        return toResponse(owned(checks.findById(id), p));
    }

    @Transactional(readOnly = true)
    public List<DateCheckSummaryDto> list(AuthPrincipal p, Integer limit) {
        access.requireEnabled(p.orgId());
        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        List<DateCheck> page = checks.findByOrgIdAndOriginOrderByCreatedAtDesc(p.orgId(), DateCheck.ORIGIN_ORGANIZER,
                PageRequest.of(0, n));
        if (page.isEmpty()) return List.of();
        Map<UUID, List<DateCheckDate>> byCheck = dates
                .findByDateCheckIdInOrderByCandidateDateAsc(page.stream().map(DateCheck::getId).toList()).stream()
                .collect(Collectors.groupingBy(DateCheckDate::getDateCheckId));
        return page.stream().map(c -> new DateCheckSummaryDto(c.getId(), c.getStatus(), c.getCity(), c.getGenreFamily(),
                c.getCreatedAt(), byCheck.getOrDefault(c.getId(), List.of()).stream()
                .map(d -> new DateCheckSummaryDto.DateSummary(d.getCandidateDate(), d.getVerdict(), d.getRiskScore(),
                        d.getRankOrder() == null ? null : d.getRankOrder().intValue()))
                .toList())).toList();
    }

    /** A fresh rule-engine run on the current bank with the merged answers; the old rows are replaced. */
    @Transactional
    public DateCheckResponse patchAssumptions(AuthPrincipal p, UUID id, AssumptionsPatch patch) {
        access.requireEnabled(p.orgId());
        rateLimiter.consume("predictor-date-check", p.actorLabel());
        DateCheck c = owned(checks.findLockedById(id), p);
        List<DateCheckDate> old = dates.findByDateCheckIdOrderByCandidateDateAsc(c.getId());
        List<LocalDate> candidates = old.stream().map(DateCheckDate::getCandidateDate).toList();
        LocalDate today = validator.validatePatch(patch, c.getCountry(), candidates);

        Answers stored = storedAnswers(c);
        Answers merged = new Answers(
                patch.audienceAge() != null ? patch.audienceAge() : stored.audienceAge(),
                patch.communities() != null ? upper(patch.communities()) : stored.communities(),
                patch.buyingLeadDays() != null ? patch.buyingLeadDays() : stored.buyingLeadDays());
        if (patch.priceMinor() != null) c.setPriceMinor(patch.priceMinor());
        if (patch.startHour() != null) c.setStartHour(patch.startHour().shortValue());

        findings.deleteByDateCheckDateIdIn(old.stream().map(DateCheckDate::getId).toList());
        dates.deleteByDateCheckId(c.getId());
        // The unique (check, date) key needs the deletes flushed before the re-insert.
        dates.flush();

        DateCheckInput in = input(c, today, merged);
        c.setAssumptionsJson(write(AssumptionResolver.resolve(in, profile(c))));
        c.setQuestionBankVersion(bank.version());
        c.setUpdatedAt(Times.nowMicros());
        checks.saveAndFlush(c);
        run(c, in, candidates);
        return toResponse(c);
    }

    // --- event link ---

    /** A check the caller's org may link a new event to; a closed gate, unknown or foreign id is the same 404. */
    @Transactional
    public DateCheck requireLinkable(AuthPrincipal p, UUID id) {
        access.requireEnabled(p.orgId());
        return owned(checks.findLockedById(id), p);
    }

    /**
     * Records the event on the check unless it already names one; the first event made from a check keeps it.
     * Linking is not a re-score, so updated_at stays; a managed instance is refreshed so no later save reverts it.
     */
    @Transactional
    public void stampEvent(DateCheck c, UUID eventId) {
        if (c.getEventId() != null) return;
        int linked = checks.linkEventIfUnset(c.getId(), eventId);
        if (entityManager.contains(c)) entityManager.refresh(c);
        else if (linked == 1) c.setEventId(eventId);
    }

    /** The bank's spelling of a sub-genre, matched case-insensitively; 400 when no genre profile lists it. */
    public String requireKnownSubGenre(String subGenre) {
        return bank.profiles().values().stream()
                .filter(gp -> gp != null && gp.subGenres() != null)
                .flatMap(gp -> gp.subGenres().stream())
                .filter(sg -> sg.equalsIgnoreCase(subGenre))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                        "Unknown sub-genre", Map.of("subGenre", "unknown")));
    }

    /**
     * The event's current check among the one it was created from and those made for it: one scoring its night
     * first, then the most recently scored; matched in the check's own zone. Empty while the gate is closed for the org, with no linked check, or no dates.
     */
    @Transactional(readOnly = true)
    public Optional<EventDateCheckDto> currentForEvent(Event e) {
        if (!access.isEnabled(e.getOrgId()) || e.getId() == null) return Optional.empty();
        Optional<DateCheck> current = currentCheck(e);
        if (current.isEmpty()) return Optional.empty();
        DateCheck c = current.get();

        List<DateCheckDate> rows = dates.findByDateCheckIdOrderByCandidateDateAsc(c.getId());
        DateCheckStaleness.Match m = DateCheckStaleness.match(rows, e.getStartsAt(), zoneOf(c));
        if (m == null) return Optional.empty();
        List<DateCheckFinding> rowFindings = findings.findByDateCheckDateIdIn(List.of(m.row().getId()));
        DateCheckDateDto result = dateDto(m.row(), rowFindings, new BankView(bank));
        return Optional.of(new EventDateCheckDto(c.getId(), result, c.getUpdatedAt(), m.row().getCandidateDate(),
                m.stale()));
    }

    /** Checks that score a date this close to the event's UTC date are the only ones that can score its night. */
    private static final int NIGHT_SEARCH_DAYS_BEFORE = 2;
    private static final int NIGHT_SEARCH_DAYS_AFTER = 1;
    /**
     * ponytail: the 10 newest checks with a date in UTC day -2..+1; more than 10 newer ones that miss the night in
     * their own zone would hide an older one that scores it. An event gets a handful, so the cap holds.
     */
    private static final int NIGHT_CANDIDATES = 10;

    /**
     * A check that scores the event's night beats one that does not; then newest updatedAt, createdAt and id
     * (as a string, matching the database's ordering), so a re-scored organizer check beats an older radar run.
     */
    private Optional<DateCheck> currentCheck(Event e) {
        Map<UUID, DateCheck> candidates = new LinkedHashMap<>();
        checks.findFirstByOrgIdAndEventIdOrderByUpdatedAtDescCreatedAtDescIdDesc(e.getOrgId(), e.getId())
                .ifPresent(c -> candidates.put(c.getId(), c));
        if (e.getStartsAt() != null) {
            LocalDate utcDay = e.getStartsAt().atZone(ZoneOffset.UTC).toLocalDate();
            checks.findEventChecksWithDateBetween(e.getOrgId(), e.getId(), utcDay.minusDays(NIGHT_SEARCH_DAYS_BEFORE),
                            utcDay.plusDays(NIGHT_SEARCH_DAYS_AFTER), PageRequest.of(0, NIGHT_CANDIDATES))
                    .forEach(c -> candidates.putIfAbsent(c.getId(), c));
        }
        if (e.getDateCheckId() != null) {
            checks.findById(e.getDateCheckId()).filter(c -> e.getOrgId().equals(c.getOrgId()))
                    .ifPresent(c -> candidates.putIfAbsent(c.getId(), c));
        }
        if (candidates.isEmpty()) return Optional.empty();
        Map<UUID, List<DateCheckDate>> rows = dates
                .findByDateCheckIdInOrderByCandidateDateAsc(List.copyOf(candidates.keySet())).stream()
                .collect(Collectors.groupingBy(DateCheckDate::getDateCheckId));
        return candidates.values().stream().max(Comparator
                .comparing((DateCheck c) -> scoresNight(rows.getOrDefault(c.getId(), List.of()), e, c))
                .thenComparing(DateCheck::getUpdatedAt)
                .thenComparing(DateCheck::getCreatedAt)
                .thenComparing(c -> c.getId().toString()));
    }

    private static boolean scoresNight(List<DateCheckDate> rowsByDateAsc, Event e, DateCheck c) {
        DateCheckStaleness.Match m = DateCheckStaleness.match(rowsByDateAsc, e.getStartsAt(), zoneOf(c));
        return m != null && !m.stale();
    }

    // --- radar ---

    public enum RadarOutcome { RAN, NOT_DUE, NO_CHECK, GATE_CLOSED }

    /** Re-runs the event's current check for its night when a radar milestone is due; one transaction per event. */
    @Transactional
    public RadarOutcome radarRerun(UUID eventId) {
        Event e = events.findById(eventId)
                .filter(x -> x.getDeletedAt() == null && x.getStatus() == EventStatus.LIVE && x.getStartsAt() != null)
                .orElse(null);
        if (e == null) return RadarOutcome.NOT_DUE;
        if (!access.isEnabled(e.getOrgId())) return RadarOutcome.GATE_CLOSED;
        DateCheck prev = currentCheck(e).orElse(null);
        if (prev == null) return RadarOutcome.NO_CHECK;
        ZoneId zone = zoneOf(prev);
        LocalDate night = NightDates.nightOf(e.getStartsAt(), zone);
        LocalDate today = validator.today(prev.getCountry());
        OptionalInt m = ReforecastMilestones.radarMilestoneDue(ChronoUnit.DAYS.between(today, night));
        if (m.isEmpty()) return RadarOutcome.NOT_DUE;
        DateCheckStaleness.Match match = DateCheckStaleness.match(
                dates.findByDateCheckIdOrderByCandidateDateAsc(prev.getId()), e.getStartsAt(), zone);
        boolean scoresNight = match != null && !match.stale();
        if (scoresNight && ReforecastMilestones.radarDone(prev.getUpdatedAt().atZone(zone).toLocalDate(), night,
                m.getAsInt())) {
            return RadarOutcome.NOT_DUE;
        }
        DateCheck c = radarCopy(prev, e.getId(), m.getAsInt(), night);
        DateCheckInput in = input(c, today, storedAnswers(prev));
        c.setAssumptionsJson(write(AssumptionResolver.resolve(in, profile(c))));
        c.setQuestionBankVersion(bank.version());
        // uq_date_check_radar_run is the backstop against a second writer.
        checks.saveAndFlush(c);
        run(c, in, List.of(night));
        return RadarOutcome.RAN;
    }

    /** A new radar row with the previous check's inputs, timestamped by the injected clock. */
    private DateCheck radarCopy(DateCheck prev, UUID eventId, int milestone, LocalDate night) {
        DateCheck c = new DateCheck();
        c.setOrgId(prev.getOrgId());
        c.setCreatedBy(prev.getCreatedBy());
        c.setCity(prev.getCity());
        c.setCountry(prev.getCountry());
        c.setPostalCode(prev.getPostalCode());
        c.setGenreFamily(prev.getGenreFamily());
        c.setSubGenre(prev.getSubGenre());
        c.setCapacity(prev.getCapacity());
        c.setPriceMinor(prev.getPriceMinor());
        c.setFormat(prev.getFormat());
        c.setStartHour(prev.getStartHour());
        c.setEndHour(prev.getEndHour());
        c.setLineupJson(prev.getLineupJson());
        c.setKnownEventsJson(prev.getKnownEventsJson());
        c.setEventId(eventId);
        c.setResearch(false);
        c.setStatus(STATUS_DONE);
        c.setOrigin(DateCheck.ORIGIN_RADAR);
        c.setRadarMilestone((short) milestone);
        c.setRadarNight(night);
        c.setRadarPrevId(prev.getId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    /** The zone the check was scored in, as {@link DateCheckInput#zone()} resolves it. */
    private static ZoneId zoneOf(DateCheck c) {
        return CountryTimeZones.zoneFor(c.getCountry()).<ZoneId>map(ZoneId::of).orElse(ZoneOffset.UTC);
    }

    public DateCheckConfigResponse config(AuthPrincipal p) {
        access.requireEnabled(p.orgId());
        List<DateCheckConfigResponse.Genre> genres = QuestionBank.GENRE_BUCKETS.stream()
                .map(b -> {
                    GenreProfile gp = bank.profiles().get(b);
                    return new DateCheckConfigResponse.Genre(b, gp == null ? List.of() : gp.subGenres());
                })
                .toList();
        return new DateCheckConfigResponse(Boolean.TRUE.equals(props.getResearchEnabled()), genres,
                props.getMaxDates(), props.getMaxHorizonMonths());
    }

    // --- run ---

    private void run(DateCheck c, DateCheckInput in, List<LocalDate> candidates) {
        LocalDate today = in.today();
        List<Ranker.Candidate> scored = new ArrayList<>();
        Map<LocalDate, List<Finding>> findingsByDate = new LinkedHashMap<>();
        Map<LocalDate, List<ActionItem>> actionsByDate = new HashMap<>();
        for (LocalDate d : candidates) {
            List<Finding> fs = engine.evaluate(in, d);
            DateResult result = Scorer.score(fs, bank);
            findingsByDate.put(d, fs);
            actionsByDate.put(d, ActionPicker.pick(fs, bank, d, today));
            scored.add(new Ranker.Candidate(d, result, (int) ChronoUnit.DAYS.between(today, d)));
        }
        List<Ranker.Ranked> ranked = Ranker.rank(scored);

        List<Map<String, Object>> output = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Ranker.Candidate cand = scored.get(i);
            DateResult result = cand.result();
            Integer rank = ranked.get(i).rank();
            DateCheckDate row = new DateCheckDate();
            row.setDateCheckId(c.getId());
            row.setCandidateDate(cand.date());
            row.setVerdict(result.verdict().dbValue());
            row.setRiskScore((short) result.riskScore());
            row.setOppScore((short) result.oppScore());
            row.setCoverage(result.coverage());
            row.setRankOrder(rank == null ? null : rank.shortValue());
            row.setActionsJson(write(actionsByDate.get(cand.date()).stream()
                    .map(a -> new ActionDto(a.key(), a.dueDate(), a.questionId(), a.params()))
                    .toList()));
            UUID dateId = dates.save(row).getId();
            findings.saveAll(findingsByDate.get(cand.date()).stream().map(f -> findingRow(dateId, f)).toList());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("date", cand.date().toString());
            out.put("verdict", row.getVerdict());
            out.put("riskScore", result.riskScore());
            out.put("oppScore", result.oppScore());
            out.put("coverage", result.coverage());
            out.put("rank", rank);
            output.add(out);
        }

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("input", in);
        canonical.put("dates", candidates.stream().map(LocalDate::toString).toList());
        ledger.recordDateCheck(c.getOrgId(), c.getId(), bank.version(), sha256(write(canonical)),
                write(Map.of("dates", output)));
    }

    private static DateCheckFinding findingRow(UUID dateId, Finding f) {
        DateCheckFinding row = new DateCheckFinding();
        row.setDateCheckDateId(dateId);
        row.setQuestionId(f.questionId());
        row.setKind(wire(f.kind()));
        row.setStatus(wire(f.status()));
        row.setStrength((short) f.strength());
        row.setWeight((short) f.weight());
        row.setSourceKind(wire(f.sourceKind()));
        row.setTimeWindow(wire(f.window()));
        row.setStopFactor(f.stopFactor());
        row.setFactsJson(write(f.facts()));
        row.setUrl(f.url());
        row.setQuote(f.quote());
        row.setFetchedAt(f.fetchedAt());
        return row;
    }

    private DateCheckInput input(DateCheck c, LocalDate today, Answers a) {
        return new DateCheckInput(c.getCity(), c.getCountry(), c.getPostalCode(), null, null, c.getGenreFamily(),
                c.getSubGenre(), c.getCapacity(), c.getPriceMinor(), c.getFormat(),
                c.getStartHour() == null ? null : c.getStartHour().intValue(),
                c.getEndHour() == null ? null : c.getEndHour().intValue(),
                c.getLineupJson() == null ? null : read(c.getLineupJson(), STRINGS),
                c.getKnownEventsJson() == null ? null : read(c.getKnownEventsJson(), KNOWN),
                c.getOrgId(), today, a.audienceAge(), a.communities(), a.buyingLeadDays(), c.getEventId());
    }

    private GenreProfile profile(DateCheck c) {
        return bank.profiles().get(c.getGenreFamily());
    }

    private static Answers storedAnswers(DateCheck c) {
        List<Integer> age = null;
        List<String> communities = null;
        Integer lead = null;
        for (Assumption a : read(c.getAssumptionsJson(), ASSUMPTIONS)) {
            if (a.source() != Assumption.Source.ORGANIZER) continue;
            switch (a.field()) {
                case AUDIENCE_AGE -> age = PredictorJson.MAPPER.convertValue(a.value(), INTS);
                case COMMUNITIES -> communities = PredictorJson.MAPPER.convertValue(a.value(), STRINGS);
                case BUYING_LEAD_DAYS -> lead = PredictorJson.MAPPER.convertValue(a.value(), Integer.class);
                default -> { }
            }
        }
        return new Answers(age, communities, lead);
    }

    // --- read ---

    private static DateCheck owned(java.util.Optional<DateCheck> c, AuthPrincipal p) {
        return c.filter(x -> p.orgId().equals(x.getOrgId())).orElseThrow(() -> ApiException.notFound("Date check"));
    }

    private DateCheckResponse toResponse(DateCheck c) {
        List<DateCheckDate> rows = dates.findByDateCheckIdOrderByCandidateDateAsc(c.getId());
        Map<UUID, List<DateCheckFinding>> byDate = rows.isEmpty() ? Map.of()
                : findings.findByDateCheckDateIdIn(rows.stream().map(DateCheckDate::getId).toList()).stream()
                .collect(Collectors.groupingBy(DateCheckFinding::getDateCheckDateId));
        BankView view = new BankView(bank);
        List<DateCheckDateDto> dateDtos = rows.stream()
                .map(d -> dateDto(d, byDate.getOrDefault(d.getId(), List.of()), view))
                .toList();
        List<AssumptionDto> assumptions = read(c.getAssumptionsJson(), ASSUMPTIONS).stream()
                .map(a -> new AssumptionDto(fieldName(a.field()), a.value(), wire(a.source()), a.estimate(),
                        a.sourcedUrl()))
                .toList();
        return new DateCheckResponse(c.getId(), c.getStatus(), c.getCity(), c.getCountry(), c.getPostalCode(),
                c.getEventId(), c.getGenreFamily(), c.getSubGenre(), c.getCapacity(), c.getPriceMinor(),
                c.isResearch(), RESEARCH_OFF, c.getQuestionBankVersion(), c.getCreatedAt(), c.getUpdatedAt(),
                assumptions, dateDtos);
    }

    private DateCheckDateDto dateDto(DateCheckDate d, List<DateCheckFinding> rows, BankView view) {
        List<DateCheckFinding> ordered = rows.stream().sorted(view.order()).toList();
        int maxPoints = bank.thresholds().maxPointsPerFinding();

        List<BreakdownLine> breakdown = ordered.stream()
                .filter(f -> "found".equals(f.getStatus()))
                .map(f -> new BreakdownLine(f.getQuestionId(), f.getKind(), f.getSourceKind(),
                        Math.min(maxPoints, f.getStrength() * f.getWeight())))
                .sorted(Comparator.comparingInt(BreakdownLine::points).reversed()
                        .thenComparing(BreakdownLine::questionId)
                        .thenComparing(l -> SourceKind.valueOf(l.sourceKind().toUpperCase(Locale.ROOT))))
                .toList();

        List<FindingDto> findingDtos = new ArrayList<>();
        List<NotChecked> notChecked = new ArrayList<>();
        Set<String> applicable = new TreeSet<>();
        Set<String> checked = new HashSet<>();
        for (DateCheckFinding f : ordered) {
            Map<String, Object> facts = read(f.getFactsJson(), FACTS);
            findingDtos.add(new FindingDto(f.getQuestionId(), f.getKind(), f.getStatus(), f.getStrength(),
                    f.getWeight(), f.getSourceKind(), f.getTimeWindow(), f.isStopFactor(), view.templateKey(f),
                    facts, f.getUrl(), f.getQuote(), f.getFetchedAt()));
            if ("not_checked".equals(f.getStatus())) {
                Object reason = facts.get("reason");
                notChecked.add(new NotChecked(f.getQuestionId(), f.getSourceKind(),
                        reason == null ? null : reason.toString()));
            }
            if (view.starIds().contains(f.getQuestionId())) {
                applicable.add(f.getQuestionId());
                if (!"not_checked".equals(f.getStatus())) checked.add(f.getQuestionId());
            }
        }
        return new DateCheckDateDto(d.getCandidateDate(), d.getVerdict(), d.getRiskScore(), d.getOppScore(),
                d.getCoverage(), wire(Ranker.bucket(d.getCoverage())),
                d.getRankOrder() == null ? null : d.getRankOrder().intValue(), checked.size(), applicable.size(),
                breakdown, findingDtos, notChecked, read(d.getActionsJson(), ACTIONS));
    }

    /** Bank lookups for mapping stored findings: bank order, star ids and each question's template key. */
    private static final class BankView {
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Question> byKey = new HashMap<>();
        private final Map<String, Set<Kind>> kindsByTemplate = new HashMap<>();
        private final Set<String> starIds = new HashSet<>();

        BankView(QuestionBank bank) {
            List<Question> qs = bank.questions();
            for (int i = 0; i < qs.size(); i++) {
                Question q = qs.get(i);
                String key = key(q.id(), wire(q.source()));
                index.putIfAbsent(key, i);
                byKey.putIfAbsent(key, q);
                kindsByTemplate.computeIfAbsent(q.template(), t -> new HashSet<>()).addAll(q.kinds());
                if (q.star()) starIds.add(q.id());
            }
        }

        Set<String> starIds() {
            return starIds;
        }

        Comparator<DateCheckFinding> order() {
            return Comparator.comparingInt((DateCheckFinding f) ->
                            index.getOrDefault(key(f.getQuestionId(), f.getSourceKind()), Integer.MAX_VALUE))
                    .thenComparing(DateCheckFinding::getQuestionId)
                    .thenComparing(DateCheckFinding::getSourceKind);
        }

        /** Same rule as {@link QuestionBank#templateKeys()}; null for a question the current bank dropped. */
        String templateKey(DateCheckFinding f) {
            Question q = byKey.get(key(f.getQuestionId(), f.getSourceKind()));
            if (q == null) return null;
            return kindsByTemplate.get(q.template()).size() > 1 ? q.template() + "." + f.getKind() : q.template();
        }

        private static String key(String id, String source) {
            return id + "|" + source;
        }
    }

    // --- helpers ---

    private static String fieldName(Assumption.Field f) {
        return switch (f) {
            case AUDIENCE_AGE -> "audienceAge";
            case COMMUNITIES -> "communities";
            case PRICE_MINOR -> "priceMinor";
            case START_HOUR -> "startHour";
            case BUYING_LEAD_DAYS -> "buyingLeadDays";
        };
    }

    private static String wire(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static List<String> upper(List<String> codes) {
        return codes == null ? null : codes.stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).toList();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String write(Object o) {
        try {
            return PredictorJson.MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("date check: could not serialise " + o.getClass().getSimpleName(), e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return PredictorJson.MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("date check: unreadable stored JSON", e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
