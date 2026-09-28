package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.PortraitExecutor;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitTown;
import com.imin.iminapi.audienceplan.service.PortraitLlmClient.Citation;
import com.imin.iminapi.audienceplan.service.PortraitLlmClient.Reply;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Pair;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Row;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Spend;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.StoredGroup;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * LLM research for a (genre, city) portrait: a web-search call, then a no-tools extraction, vetted by code
 * ({@link PortraitResearchParser}). Runs lazily after a portrait or plan GET and from {@link PortraitRefreshJob};
 * never from a plan refresh, the plan list or Momentum. Prompts hold public place and genre names only.
 */
@Service
public class PortraitResearchService {

    private static final Logger log = LoggerFactory.getLogger(PortraitResearchService.class);

    static final Duration TTL = Duration.ofDays(90);
    /** A run that gave nothing usable is retried by a GET after this. */
    static final Duration EMPTY_RETRY = Duration.ofDays(1);
    static final int CALLS_PER_PORTRAIT = 2;
    static final int MAX_NOTES_CHARS = 12_000;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** What one research run ended with. */
    /** SKIPPED: the city has no known catchment; BUSY: the pair is already being researched. Neither writes. */
    public enum Outcome { READY, EMPTY, CAPPED, SKIPPED, BUSY }

    private final PortraitLlmClient llm;
    private final LlmPayloadGuard guard;
    private final PortraitResearchParser parser;
    private final PortraitResearchStore store;
    private final PortraitService portraits;
    private final AudiencePlanProperties props;
    private final Executor executor;
    private final Clock clock;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    // ponytail: per instance and reset on restart, so N instances allow N x the caps; move to a table to scale out.
    private final Map<UUID, Integer> orgCalls = new HashMap<>();
    private int globalCalls;
    private LocalDate capDay;

    public PortraitResearchService(PortraitLlmClient llm, LlmPayloadGuard guard, IdentityLabelGuard identity,
                                   PortraitResearchStore store, PortraitService portraits, AudiencePlanProperties props,
                                   @Qualifier(PortraitExecutor.NAME) Executor executor, Clock clock) {
        this.llm = llm;
        this.guard = guard;
        this.parser = new PortraitResearchParser(identity);
        this.store = store;
        this.portraits = portraits;
        this.props = props;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Called after a portrait or plan GET: records the request and schedules research when the pair has none yet,
     * or its last run gave nothing and the retry time passed. A city without a known catchment is neither recorded
     * nor researched. Never throws into the request.
     */
    public void requestIfMissing(UUID orgId, String genreKey, String cityKey) {
        try {
            if (portraits.openData(genreKey, cityKey).catchment() == null) return;
            Row row = store.touch(genreKey, cityKey, clock.instant());
            if (!due(row, clock.instant())) return;
            String key = genreKey + "|" + cityKey;
            if (!inFlight.add(key)) return;
            try {
                executor.execute(() -> {
                    try {
                        research(orgId, genreKey, cityKey);
                    } catch (Exception e) {
                        log.warn("PortraitResearch: {} / {} not researched; a later GET retries: {} {}", genreKey,
                                cityKey, e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
                    } finally {
                        inFlight.remove(key);
                    }
                });
            } catch (RejectedExecutionException e) {
                inFlight.remove(key);
                log.warn("PortraitResearch: queue full, {} / {} skipped; a later GET retries", genreKey, cityKey);
            }
        } catch (Exception e) {
            log.warn("PortraitResearch: request for {} / {} not recorded: {} {}", genreKey, cityKey,
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    /** The refresh job's run: skipped while a GET-triggered run of the same pair is in flight, and vice versa. */
    public Outcome refresh(String genreKey, String cityKey) {
        String key = genreKey + "|" + cityKey;
        if (!inFlight.add(key)) return Outcome.BUSY;
        try {
            return research(null, genreKey, cityKey);
        } finally {
            inFlight.remove(key);
        }
    }

    /** Ready research waits for the weekly refresh; a pending pair or an empty one past its retry is due now. */
    static boolean due(Row row, Instant now) {
        if (PortraitResearchStore.PENDING.equals(row.status())) return true;
        return PortraitResearchStore.EMPTY.equals(row.status())
                && (row.expiresAt() == null || !row.expiresAt().isAfter(now));
    }

    /**
     * One research run, synchronously. {@code orgId} is the requesting org, or null for the refresh job (global cap
     * only). CAPPED writes nothing; any refusal, empty or unusable answer, guard refusal or error stores EMPTY.
     */
    public Outcome research(UUID orgId, String genreKey, String cityKey) {
        Instant now = clock.instant();
        Pair pair = new Pair(genreKey, cityKey);
        AudiencePortraitResponse open = portraits.openData(genreKey, cityKey);
        // Only a resolved catchment reaches a prompt, never the raw city text an organizer typed.
        if (open.catchment() == null || open.catchment().towns().isEmpty()) return Outcome.SKIPPED;
        Map<String, String> towns = towns(open);
        String model = props.getPortraitModel();
        // A 5xx retry is one more call, so it needs room under both caps.
        java.util.function.BooleanSupplier mayRetry = () -> takeCalls(orgId, 1);

        String researchSystem = researchSystem();
        String researchUser = researchUser(genreKey, cityKey, open);
        if (!passes(researchSystem + "\n" + researchUser, pair)) return empty(pair, now, spend(model, List.of()));
        if (!takeCalls(orgId, CALLS_PER_PORTRAIT)) {
            log.info("PortraitResearch: daily cap reached; {} / {} skipped", genreKey, cityKey);
            return Outcome.CAPPED;
        }

        List<Reply> replies = new ArrayList<>();
        try {
            Reply notes = llm.research(model, researchSystem, researchUser, mayRetry);
            replies.add(notes);
            if (!notes.usable()) {
                log.warn("PortraitResearch: research for {} / {} refused, paused or empty ({})", genreKey, cityKey,
                        notes.finishReason());
                return empty(pair, now, spend(model, replies));
            }
            String extractSystem = extractSystem();
            String extractUser = extractUser(genreKey, towns, notes);
            if (!passes(extractSystem + "\n" + extractUser, pair)) return empty(pair, now, spend(model, replies));

            Reply answer = llm.extract(model, extractSystem, extractUser, mayRetry);
            replies.add(answer);
            List<StoredGroup> groups = answer.usable()
                    ? parser.parse(answer.text(), notes.citations(), towns) : List.of();
            Spend spend = spend(model, replies);
            log.info("PortraitResearch: {} / {} model {} tokens {}/{} cost {} → {} group(s)", genreKey, cityKey,
                    spend.model(), spend.tokensIn(), spend.tokensOut(), spend.costUsd(), groups.size());
            if (groups.isEmpty()) return empty(pair, now, spend);
            store.saveReady(pair, groups, now, now.plus(TTL), spend);
            return Outcome.READY;
        } catch (Exception e) {
            log.warn("PortraitResearch: LLM call for {} / {} failed: {} {}", genreKey, cityKey,
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
            return empty(pair, now, spend(model, replies));
        }
    }

    /** The prompts carry no org data, so no member names are checked; emails and phones still are. */
    private boolean passes(String payload, Pair pair) {
        try {
            guard.check(payload, null);
            return true;
        } catch (LlmPayloadGuard.Rejected e) {
            log.warn("PortraitResearch: payload guard refused {} / {}: {}", pair.genreKey(), pair.cityKey(),
                    e.reason());
            return false;
        }
    }

    private Outcome empty(Pair pair, Instant now, Spend spend) {
        store.saveEmpty(pair, now, now.plus(EMPTY_RETRY), spend);
        return Outcome.EMPTY;
    }

    /** Reserves {@code n} calls from the org's and the global budget of the UTC day; false when either is short. */
    boolean takeCalls(UUID orgId, int n) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        synchronized (orgCalls) {
            if (!today.equals(capDay)) {
                orgCalls.clear();
                globalCalls = 0;
                capDay = today;
            }
            if (globalCalls + n > props.getPortraitDailyCapGlobal()) return false;
            int used = orgId == null ? 0 : orgCalls.getOrDefault(orgId, 0);
            if (orgId != null && used + n > props.getPortraitDailyCapPerOrg()) return false;
            globalCalls += n;
            if (orgId != null) orgCalls.put(orgId, used + n);
            return true;
        }
    }

    /** Tokens over every call; cost as OpenRouter reported it (search fees included), else at the configured prices. */
    Spend spend(String model, List<Reply> replies) {
        int in = 0;
        int out = 0;
        BigDecimal reported = BigDecimal.ZERO;
        boolean allReported = !replies.isEmpty();
        String used = model;
        for (Reply r : replies) {
            in += r.tokensIn();
            out += r.tokensOut();
            if (r.reportedCostUsd() == null) allReported = false;
            else reported = reported.add(r.reportedCostUsd());
            if (r.model() != null && !r.model().isBlank()) used = r.model();
        }
        if (replies.isEmpty()) return new Spend(model, 0, 0, null);
        BigDecimal cost = allReported ? reported : props.getPortraitPriceInputUsdPerMtok()
                .multiply(BigDecimal.valueOf(in))
                .add(props.getPortraitPriceOutputUsdPerMtok().multiply(BigDecimal.valueOf(out)))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
        return new Spend(used, in, out, cost.setScale(4, RoundingMode.HALF_UP));
    }

    // ── prompts ────────────────────────────────────────────────────────────

    /** Town name and key → key of the towns a group may name: the French towns of the catchment. */
    static Map<String, String> towns(AudiencePortraitResponse open) {
        Map<String, String> towns = new LinkedHashMap<>();
        if (open.catchment() != null) {
            for (PortraitTown t : open.catchment().towns()) {
                if (!t.inScope()) continue;
                towns.put(t.name(), t.cityKey());
                towns.put(t.cityKey(), t.cityKey());
            }
        }
        return towns;
    }

    static String researchSystem() {
        return """
                You research the local music scene around a town for an event organizer, using web search.
                Describe which groups of people outside the organizer's own guest list are likely to come to events
                of the given genre in this area: by music taste, age band, study or work situation and the towns
                they live in, and the scenes, venues, collectives, festivals or student bodies that gather them.
                Cite the pages you use.
                Rules:
                - Describe groups only by taste, place, age band, or study or work status. Never describe or group
                  people by ethnicity, national origin, religion, health, disability, sexuality, gender identity or
                  political views.
                - No names of private people, no email addresses, no phone numbers.
                - Do not estimate how many people there are; sizes are computed elsewhere.
                """;
    }

    static String researchUser(String genreKey, String cityKey, AudiencePortraitResponse open) {
        StringBuilder towns = new StringBuilder();
        if (open.catchment() != null) {
            for (PortraitTown t : open.catchment().towns()) {
                if (!towns.isEmpty()) towns.append(", ");
                towns.append(t.name()).append(" (").append(t.country()).append(", ").append(t.kmStraight())
                        .append(" km)");
            }
        }
        return "Genre: " + genreKey + "\nTown: " + centre(cityKey, open) + "\nTowns nearby: " + towns;
    }

    /** The catchment's own name for the requested city, else its nearest town. */
    static String centre(String cityKey, AudiencePortraitResponse open) {
        List<PortraitTown> towns = open.catchment().towns();
        return towns.stream().filter(t -> t.cityKey().equals(cityKey)).findFirst()
                .or(() -> towns.stream().min(java.util.Comparator.comparingInt(PortraitTown::kmStraight)))
                .map(PortraitTown::name).orElseThrow();
    }

    static String extractSystem() {
        return """
                You turn research notes into JSON for an event organizer. Answer with one JSON object only:
                {"groups":[{"label":"...","description":"...","basis":"...","towns":["..."],"sourceUrls":["..."]}]}
                with 2 to 4 groups taken from the NOTES only.
                - label: at most 8 words naming the group by music taste, place, age band, or study or work status.
                - description: one sentence of at most 200 characters on where and how to reach them.
                - basis: the population the group belongs to: "genre_first" (people for whom this genre is their
                  first choice), "regulars" (people who often go to such events), "students" (enrolled students)
                  or "none".
                - towns: names from TOWNS only; [] when the group is not tied to a town.
                - sourceUrls: URLs copied exactly from SOURCES that support the group; [] when none does.
                Rules: no numbers, figures or percentages anywhere. Never describe people by ethnicity, national
                origin, religion, health, disability, sexuality, gender identity or political views. No names of
                private people, no emails, no phone numbers.
                """;
    }

    static String extractUser(String genreKey, Map<String, String> towns, Reply notes) {
        StringBuilder sources = new StringBuilder();
        for (Citation c : notes.citations()) {
            sources.append("- ").append(c.url());
            if (c.title() != null && !c.title().isBlank()) sources.append(" (").append(c.title()).append(')');
            sources.append('\n');
        }
        String text = notes.text().length() > MAX_NOTES_CHARS ? notes.text().substring(0, MAX_NOTES_CHARS)
                : notes.text();
        List<String> names = towns.entrySet().stream().filter(e -> !e.getKey().equals(e.getValue()))
                .map(Map.Entry::getKey).toList();
        return "GENRE: " + genreKey + "\nTOWNS: " + (!names.isEmpty() ? String.join(", ", names)
                : towns.isEmpty() ? "(none)" : String.join(", ", new java.util.LinkedHashSet<>(towns.values())))
                + "\nSOURCES:\n" + (sources.isEmpty() ? "(none)\n" : sources) + "NOTES:\n" + text;
    }
}
