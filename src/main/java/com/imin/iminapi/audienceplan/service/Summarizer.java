package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.SummaryChatClient;
import com.imin.iminapi.audienceplan.config.SummaryExecutor;
import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.service.ai.provenance.AiEmailDisclosure;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Short plan text per locale, generated lazily after a plan GET and stored in {@code audience_plans.summaries}.
 * The model gets aggregates only (checked by {@link LlmPayloadGuard}) and may only repeat numbers it was given;
 * otherwise, and on any refusal or failure, the per-locale code template is stored instead.
 */
@Service
public class Summarizer {

    private static final Logger log = LoggerFactory.getLogger(Summarizer.class);

    static final int MAX_LINE_CHARS = 400;
    /** Member / consumer display names handed to the guard. */
    static final int NAME_SAMPLE = 5_000;
    static final int ATTEMPTS = 2;
    /** A model summary of the same event and locale younger than this is copied, not asked for again. */
    static final Duration COOLDOWN = Duration.ofHours(24);
    /** Earlier plans of the event searched for such a summary. */
    static final int COOLDOWN_PLANS = 20;
    private static final double TEMPERATURE = 0.2;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final Map<String, String> LANGUAGE = Map.of(
            "en", "English", "es", "Spanish", "fr", "French", "uk", "Ukrainian");

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final TypeReference<LinkedHashMap<String, AudiencePlanResponse.Summary>> SUMMARIES =
            new TypeReference<>() {};

    private final ChatClient chat;
    private final LlmPayloadGuard guard;
    private final AudiencePlanProperties props;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final Clock clock;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    // ponytail: per instance and reset on restart, so N instances allow N x the cap; move to a table to scale out.
    private final Map<UUID, Integer> callsToday = new HashMap<>();
    private LocalDate capDay;

    public Summarizer(@Qualifier(SummaryChatClient.NAME) ChatClient chat, LlmPayloadGuard guard, AudiencePlanProperties props,
                      JdbcTemplate jdbc, TransactionTemplate tx,
                      @Qualifier(SummaryExecutor.NAME) Executor executor, Clock clock) {
        this.chat = chat;
        this.guard = guard;
        this.props = props;
        this.jdbc = jdbc;
        this.tx = tx;
        this.executor = executor;
        this.clock = clock;
    }

    /** Token use of one or more calls; cost is null when the prices are not configured. */
    record Spend(int tokensIn, int tokensOut, int calls) {
        static final Spend NONE = new Spend(0, 0, 0);

        Spend plus(Usage u) {
            int in = u == null || u.getPromptTokens() == null ? 0 : u.getPromptTokens();
            int out = u == null || u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
            return new Spend(tokensIn + in, tokensOut + out, calls + 1);
        }
    }

    /** One locale's result: the summary to store and what producing it spent. */
    record Generated(AudiencePlanResponse.Summary summary, Spend spend) {}

    /** The model's JSON answer before checks. */
    record Draft(String headline, List<String> segmentLines, String gapLine, List<String> actions,
                 List<String> assumptions) {}

    /**
     * Called after a plan GET committed: schedules one summary for this plan and locale unless it is stored,
     * switched off, or already on its way. Never throws into the request.
     */
    public void requestIfMissing(UUID orgId, AudiencePlanResponse plan, String rawLocale) {
        if (!Boolean.TRUE.equals(props.getSummaryEnabled()) || plan.summary() != null) return;
        String locale = PlanService.locale(rawLocale);
        String key = plan.id() + "/" + locale;
        if (!inFlight.add(key)) return;
        try {
            executor.execute(() -> {
                try {
                    store(plan.id(), locale, produce(orgId, plan, locale));
                } catch (Exception e) {
                    log.warn("Summarizer: summary for plan {} ({}) not stored; the next GET retries: {} {}",
                            plan.id(), locale, e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(key);
            log.warn("Summarizer: queue full, summary for plan {} ({}) skipped; the next GET retries",
                    plan.id(), locale);
        }
    }

    /** The model id summaries use; a blank setting already bound the code default. */
    String modelId() {
        return props.getSummaryModel();
    }

    /** A recent model summary of the same event and locale when there is one, else a new one. */
    Generated produce(UUID orgId, AudiencePlanResponse plan, String locale) {
        Generated recent = recent(orgId, plan, locale);
        return recent != null ? recent : generate(orgId, plan, locale);
    }

    /**
     * Within the cooldown no call is made: the latest model summary of this event and locale is copied when it
     * still fits the plan's shape and numbers, otherwise the template stands in. Null when there is none.
     */
    Generated recent(UUID orgId, AudiencePlanResponse plan, String locale) {
        Instant now = clock.instant();
        List<String> rows = jdbc.queryForList("""
                SELECT summaries FROM audience_plans
                 WHERE org_id = ? AND event_id = ? AND id <> ? AND summaries IS NOT NULL
                 ORDER BY created_at DESC LIMIT ?""", String.class, orgId, plan.eventId(), plan.id(), COOLDOWN_PLANS);
        AudiencePlanResponse.Summary latest = null;
        for (String json : rows) {
            AudiencePlanResponse.Summary s = readQuietly(json).get(locale);
            if (s == null || !s.aiGenerated() || s.generatedAt() == null) continue;
            if (latest == null || s.generatedAt().isAfter(latest.generatedAt())) latest = s;
        }
        if (latest == null || !latest.generatedAt().isAfter(now.minus(COOLDOWN))) return null;
        if (fits(latest, plan)) return new Generated(latest, Spend.NONE);
        log.info("Summarizer: recent summary for event {} ({}) no longer fits the plan; template until the cooldown ends",
                plan.eventId(), locale);
        return template(plan, locale, now, Spend.NONE);
    }

    private static boolean fits(AudiencePlanResponse.Summary s, AudiencePlanResponse plan) {
        if (!line(s.headline()) || !line(s.gapLine())) return false;
        if (!lines(s.segmentLines()) || !lines(s.actions()) || !lines(s.assumptions())) return false;
        if (s.segmentLines().size() != plan.segments().size() || s.actions().size() != plan.actions().size()) return false;
        Draft d = new Draft(s.headline(), s.segmentLines(), s.gapLine(), s.actions(), s.assumptions());
        return SummaryNumbers.invented(String.join("\n", texts(d)), SummaryNumbers.allowed(data(plan))).isEmpty();
    }

    /** Takes one call from the org's daily budget (UTC day); false once the cap is reached. */
    boolean takeCall(UUID orgId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        synchronized (callsToday) {
            if (!today.equals(capDay)) {
                callsToday.clear();
                capDay = today;
            }
            int used = callsToday.getOrDefault(orgId, 0);
            if (used >= props.getSummaryDailyCapPerOrg()) return false;
            callsToday.put(orgId, used + 1);
            return true;
        }
    }

    /** Builds the summary for one locale; falls back to the template rather than throw. */
    Generated generate(UUID orgId, AudiencePlanResponse plan, String locale) {
        Instant now = clock.instant();
        String data = data(plan);
        String system = system(locale, plan);
        try {
            guard.check(system + "\n" + user(data, true), knownNames(orgId));
        } catch (LlmPayloadGuard.Rejected e) {
            log.warn("Summarizer: payload guard refused plan {} ({}): {}", plan.id(), locale, e.reason());
            return template(plan, locale, now, Spend.NONE);
        }

        SummaryNumbers.Allowed allowed = SummaryNumbers.allowed(data);
        String model = modelId();
        Spend spend = Spend.NONE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (!takeCall(orgId)) {
                log.info("Summarizer: org {} reached its daily summary cap; template for plan {} ({})", orgId,
                        plan.id(), locale);
                return template(plan, locale, now, spend);
            }
            ChatResponse response;
            try {
                response = chat.prompt()
                        .options(OpenAiChatOptions.builder().model(model).temperature(TEMPERATURE).build())
                        .system(system)
                        .user(user(data, attempt > 0))
                        .call()
                        .chatResponse();
            } catch (Exception e) {
                log.warn("Summarizer: LLM call failed for plan {} ({}): {} {}", plan.id(), locale,
                        e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
                return template(plan, locale, now, spend);
            }
            spend = spend.plus(response == null || response.getMetadata() == null ? null
                    : response.getMetadata().getUsage());
            Draft draft = draft(response, plan);
            if (draft == null) {
                log.warn("Summarizer: refusal, empty or malformed answer for plan {} ({})", plan.id(), locale);
                return template(plan, locale, now, spend);
            }
            List<String> invented = SummaryNumbers.invented(String.join("\n", texts(draft)), allowed);
            if (invented.isEmpty()) {
                String used = response.getMetadata() != null && hasText(response.getMetadata().getModel())
                        ? response.getMetadata().getModel() : model;
                return new Generated(new AudiencePlanResponse.Summary(draft.headline().trim(),
                        trimmed(draft.segmentLines()), draft.gapLine().trim(), trimmed(draft.actions()),
                        trimmed(draft.assumptions()), locale, true, AiEmailDisclosure.DISCLOSURE_VALUE, used, now),
                        spend);
            }
            log.info("Summarizer: attempt {} for plan {} ({}) wrote {} number(s) not in its input", attempt + 1,
                    plan.id(), locale, invented.size());
        }
        return template(plan, locale, now, spend);
    }

    private static Generated template(AudiencePlanResponse plan, String locale, Instant now, Spend spend) {
        return new Generated(SummaryTemplates.summary(plan, locale, now), spend);
    }

    // ── prompt ─────────────────────────────────────────────────────────────

    private static String system(String locale, AudiencePlanResponse plan) {
        return """
                You summarise an event's audience plan for the event's organizer. Write in %s.
                Answer with one JSON object only, no other text, with these keys:
                  "headline": one sentence on what the organizer's own list can bring toward the target;
                  "segmentLines": exactly %d strings, one per entry of DATA.segments, in the same order;
                  "gapLine": one sentence on the tickets still to find beyond the list;
                  "actions": exactly %d strings, one per entry of DATA.actions, in the same order;
                  "assumptions": up to 4 short strings on the assumptions behind the estimate.
                Rules:
                - Use only numbers that appear in DATA, copied exactly. Never add, subtract, multiply, divide or
                  round, and never write a number that is not in DATA.
                - Write every low/high pair only as the whole range "low–high", never one end alone. There is no
                  middle value; never invent one.
                - Write dates only as whole dates from DATA (in words or digits), never a day or a year alone.
                - Percentages only from the fields ending in Pct.
                - If mode is "cold", say there is no list to email yet and the target has to come from new people.
                - No names of people, no promises, no emojis. Calm, plain words. Each string at most 200 characters.
                """.formatted(LANGUAGE.get(locale), plan.segments().size(), plan.actions().size());
    }

    private static String user(String data, boolean retry) {
        String note = retry
                ? "Your previous answer used a number that is not in DATA. Use only numbers from DATA.\n\n" : "";
        return note + "DATA:\n" + data;
    }

    /**
     * Aggregates only: no ids, event title or person. Middle values are left out so none can be shown alone, and
     * every range is rounded as the card shows it ({@link DisplayBounds}), so the text quotes the card's numbers.
     */
    static String data(AudiencePlanResponse p) {
        String planConfidence = DisplayBounds.planConfidence(p.segments());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("mode", p.mode());
        d.put("capacity", p.capacity());
        d.put("targetTickets", p.targetTickets());
        d.put("targetPct", p.assumptions().targetPct());
        d.put("ticketsPerOrder", p.assumptions().ticketsPerOrder());
        d.put("excludedClasses", p.assumptions().excludeSegments());
        d.put("mailable", p.mailable());
        d.put("expectedTickets", p.expected() == null ? null
                : lowHigh(DisplayBounds.count(p.expected().low(), p.expected().high(), planConfidence)));
        DisplayBounds.Range cov = DisplayBounds.percent(p.coverage().low(), p.coverage().high(), planConfidence);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("lowPct", cov.low());
        coverage.put("highPct", cov.high());
        coverage.put("verdict", p.coverage().verdict());
        d.put("coverageOfTarget", coverage);
        d.put("gapTickets", lowHigh(DisplayBounds.gap(p, planConfidence)));
        d.put("gapExceedsLocalAudience", p.gapExceedsTribe());
        List<Map<String, Object>> segs = new ArrayList<>();
        for (AudiencePlanResponse.Segment s : p.segments()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("classKey", s.classKey());
            m.put("genreFit", s.genreFit());
            m.put("eventGenre", s.reason() == null ? null : s.reason().eventGenre());
            m.put("mailable", s.mailable());
            m.put("responseRatePct", lowHigh(DisplayBounds.percent(s.rate().low(), s.rate().high(), s.confidence())));
            m.put("expectedTickets", lowHigh(DisplayBounds.count(s.expected().low(), s.expected().high(),
                    s.confidence())));
            m.put("confidence", s.confidence());
            if (s.reason() != null) m.put("classRule", classRule(s.reason()));
            segs.add(m);
        }
        d.put("segments", segs);
        d.put("smallGroupsNotShown", p.smallGroupsNotShown());
        d.put("otherGenreInvited", p.otherGenreInvited());
        d.put("otherGenreHeldBack", p.otherGenreHeldBack());
        d.put("notMailableByReason", p.exclusions());
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("today", p.timing().today());
        timing.put("eventDate", p.timing().eventDate());
        timing.put("daysToEvent", p.timing().daysToEvent());
        timing.put("eventStarted", p.timing().eventStarted());
        d.put("timing", timing);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (AudiencePortraitResponse.NewPeopleGroup g : p.newPeople()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", g.key());
            m.put("kind", g.kind());
            m.put("method", g.method());
            m.put("towns", g.cityKeys());
            m.put("people", people(g));
            groups.add(m);
        }
        d.put("newPeopleGroups", groups);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (AudiencePlanResponse.Action a : p.actions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", a.type());
            m.put("classKey", a.classKey());
            m.put("genreFit", a.genreFit());
            m.put("sendDates", a.arms() == null ? List.of() : a.arms().stream().map(x -> x.date()).toList());
            m.put("holdoutPct", a.holdoutPct());
            m.put("options", a.options());
            actions.add(m);
        }
        d.put("actions", actions);
        return write(d);
    }

    private static Map<String, Object> classRule(AudiencePlanResponse.SegmentReason r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paidOrdersMin", r.paidOrdersMin());
        m.put("paidOrdersMax", r.paidOrdersMax());
        m.put("daysSinceLastPaidMin", r.daysSinceLastPaidMin());
        m.put("daysSinceLastPaidMax", r.daysSinceLastPaidMax());
        return m;
    }

    /** As the portrait panel shows it: a context group is its low alone, an audience group a research-prior range. */
    private static Object people(AudiencePortraitResponse.NewPeopleGroup g) {
        if (g.size() == null) return null;
        if ("context".equals(g.kind())) return g.size().low();
        return lowHigh(DisplayBounds.count(g.size().low(), g.size().high(), DisplayBounds.PRIOR));
    }

    private static Map<String, Object> lowHigh(DisplayBounds.Range r) {
        return lowHigh(r.low(), r.high());
    }

    private static Map<String, Object> lowHigh(Object low, Object high) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("low", low);
        m.put("high", high);
        return m;
    }

    private List<String> knownNames(UUID orgId) {
        return jdbc.queryForList("""
                SELECT n FROM (
                  SELECT m.display_name AS n FROM memberships m WHERE m.org_id = ? AND m.display_name IS NOT NULL
                  UNION
                  SELECT c.display_name AS n FROM consumers c JOIN memberships m ON m.consumer_id = c.consumer_id
                   WHERE m.org_id = ? AND c.display_name IS NOT NULL
                ) names LIMIT ?""", String.class, orgId, orgId, NAME_SAMPLE);
    }

    // ── answer ─────────────────────────────────────────────────────────────

    /** The parsed answer, or null on refusal, content filter, empty or malformed JSON, or a wrong shape. */
    static Draft draft(ChatResponse response, AudiencePlanResponse plan) {
        if (response == null) return null;
        Generation g = response.getResult();
        if (g == null || g.getOutput() == null) return null;
        Object refusal = g.getOutput().getMetadata().get("refusal");
        if (refusal instanceof String r && !r.isBlank()) return null;
        String finish = g.getMetadata() == null ? null : g.getMetadata().getFinishReason();
        if (finish != null && finish.toUpperCase(Locale.ROOT).contains("CONTENT_FILTER")) return null;
        String text = g.getOutput().getText();
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        Draft d;
        try {
            d = JSON.readValue(text.substring(start, end + 1), Draft.class);
        } catch (JsonProcessingException e) {
            return null;
        }
        if (!line(d.headline()) || !line(d.gapLine())) return null;
        if (!lines(d.segmentLines()) || d.segmentLines().size() != plan.segments().size()) return null;
        if (!lines(d.actions()) || d.actions().size() != plan.actions().size()) return null;
        if (!lines(d.assumptions())) return null;
        return d;
    }

    private static boolean line(String s) {
        return s != null && !s.isBlank() && s.length() <= MAX_LINE_CHARS;
    }

    private static boolean lines(List<String> l) {
        if (l == null) return false;
        for (String s : l) if (!line(s)) return false;
        return true;
    }

    private static List<String> texts(Draft d) {
        List<String> all = new ArrayList<>();
        all.add(d.headline());
        all.addAll(d.segmentLines());
        all.add(d.gapLine());
        all.addAll(d.actions());
        all.addAll(d.assumptions());
        return all;
    }

    private static List<String> trimmed(List<String> l) {
        return l.stream().map(String::trim).toList();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    // ── storage ────────────────────────────────────────────────────────────

    /**
     * Adds the locale to the plan row under its lock; an existing entry is kept (a concurrent twin lost the race),
     * but its tokens and cost still count, as they were spent. A plan deleted meanwhile is skipped.
     */
    void store(UUID planId, String locale, Generated g) {
        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT summaries, tokens_in, tokens_out, cost_usd FROM audience_plans WHERE id = ? FOR UPDATE",
                    planId);
            if (rows.isEmpty()) return;
            Map<String, Object> row = rows.get(0);
            String stored = (String) row.get("summaries");
            LinkedHashMap<String, AudiencePlanResponse.Summary> all = stored == null ? new LinkedHashMap<>()
                    : read(stored);
            boolean added = all.putIfAbsent(locale, g.summary()) == null;
            Spend s = g.spend();
            Integer tokensIn = sum((Number) row.get("tokens_in"), s.tokensIn(), s.calls());
            Integer tokensOut = sum((Number) row.get("tokens_out"), s.tokensOut(), s.calls());
            BigDecimal cost = cost((BigDecimal) row.get("cost_usd"), s);
            String model = added && g.summary().aiGenerated() ? g.summary().model() : null;
            jdbc.update("UPDATE audience_plans SET summaries = ?, tokens_in = ?, tokens_out = ?, cost_usd = ?,"
                    + " model_id = COALESCE(?, model_id) WHERE id = ?",
                    write(all), tokensIn, tokensOut, cost, model, planId);
        });
    }

    private static Integer sum(Number stored, int add, int calls) {
        if (calls == 0) return stored == null ? null : stored.intValue();
        return (stored == null ? 0 : stored.intValue()) + add;
    }

    /** Stored cost plus this spend at the configured prices; unchanged when either price is not set. */
    private BigDecimal cost(BigDecimal stored, Spend s) {
        BigDecimal in = props.getSummaryPriceInputUsdPerMtok();
        BigDecimal out = props.getSummaryPriceOutputUsdPerMtok();
        if (s.calls() == 0 || in == null || out == null) return stored;
        BigDecimal spent = in.multiply(BigDecimal.valueOf(s.tokensIn()))
                .add(out.multiply(BigDecimal.valueOf(s.tokensOut())))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
        return (stored == null ? BigDecimal.ZERO : stored).add(spent).setScale(4, RoundingMode.HALF_UP);
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write summary JSON", e);
        }
    }

    /** Earlier rows only serve as a cache here, so an unreadable one counts as empty. */
    private static Map<String, AudiencePlanResponse.Summary> readQuietly(String json) {
        try {
            return JSON.readValue(json, SUMMARIES);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static LinkedHashMap<String, AudiencePlanResponse.Summary> read(String json) {
        try {
            return JSON.readValue(json, SUMMARIES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read summaries JSON", e);
        }
    }
}
