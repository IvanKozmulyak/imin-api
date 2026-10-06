package com.imin.iminapi.predictor.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audienceplan.service.LlmPayloadGuard;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.research.FindingValidator.Cited;
import com.imin.iminapi.predictor.research.FindingValidator.Reported;
import com.imin.iminapi.predictor.research.ResearchLlmClient.Reply;
import com.imin.iminapi.predictor.rules.Finding;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cite-only web research for one date check: cache, prompt, payload guard, one search call, parse, validate, cache.
 * Never throws for a provider, guard or answer problem: those come back as a failed outcome, so the check keeps its
 * calendar result.
 */
@Service
public class WebResearchService {

    private static final Logger log = LoggerFactory.getLogger(WebResearchService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a research run may know about the check; the organizer's other fields never reach it. */
    public record Request(UUID orgId, String city, String country, String genreBucket, String subGenre,
                          List<LocalDate> dates) {
        public Request {
            dates = List.copyOf(dates);
        }
    }

    /**
     * {@code done} with web findings per candidate night, or failed with a reason. {@code usage} is set whenever a
     * call was made, null on a cache hit or before any call.
     */
    public record Outcome(boolean done, Map<LocalDate, List<Finding>> findings, ResearchLlmClient.Usage usage,
                          String model, String reason) {
        public Outcome {
            findings = Collections.unmodifiableMap(new LinkedHashMap<>(findings));
        }

        public static Outcome failed(String reason, ResearchLlmClient.Usage usage, String model) {
            return new Outcome(false, Map.of(), usage, model, reason);
        }
    }

    private final ResearchLlmClient client;
    private final ResearchCache cache;
    private final LlmPayloadGuard guard;
    private final QuestionBank bank;
    private final DateCheckProperties props;
    private final Clock clock;

    public WebResearchService(ResearchLlmClient client, ResearchCache cache, LlmPayloadGuard guard, QuestionBank bank,
                              DateCheckProperties props, Clock clock) {
        this.client = client;
        this.cache = cache;
        this.guard = guard;
        this.bank = bank;
        this.props = props;
        this.clock = clock;
    }

    public Outcome research(Request req) {
        String model = props.getResearchModel();
        if (req.dates().isEmpty()) return Outcome.failed("no_dates", null, model);
        LocalDate first = Collections.min(req.dates());
        LocalDate last = Collections.max(req.dates());
        // ponytail: one span from the first to the last night, so far-apart dates widen a single search.
        ResearchPrompt.Input in = new ResearchPrompt.Input(req.city(), req.country(), req.genreBucket(),
                req.subGenre(), first.minusDays(ResearchPrompt.SPAN_DAYS), last.plusDays(ResearchPrompt.SPAN_DAYS));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ResearchCache.Key key = new ResearchCache.Key(req.orgId(), req.city(), req.country(), req.genreBucket(),
                req.subGenre(), in.from(), in.to(), now.atZone(ZoneOffset.UTC).toLocalDate());
        var hit = cache.get(key);
        if (hit.isPresent()) {
            return new Outcome(true, assign(hit.get().items(), req.dates(), hit.get().fetchedAt()), null, model,
                    null);
        }

        String system = ResearchPrompt.system();
        String user = ResearchPrompt.user(in);
        try {
            guard.check(system + "\n" + user, List.of());
        } catch (LlmPayloadGuard.Rejected e) {
            log.warn("Date-check research {} not sent: {}", req.orgId(), e.getMessage());
            return Outcome.failed("guard", null, model);
        }

        Reply reply;
        try {
            reply = client.research(model, system, user);
        } catch (RuntimeException e) {
            log.warn("Date-check research call failed for org {}: {}: {}", req.orgId(), e.getClass().getSimpleName(),
                    LogSafe.redact(e.getMessage()));
            return Outcome.failed("provider", null, model);
        }
        if (!reply.usable()) {
            log.warn("Date-check research answer unusable for org {} (finish {})", req.orgId(), reply.finishReason());
            return Outcome.failed("unusable", reply.usage(), model);
        }
        List<Reported> reported;
        try {
            reported = parse(reply.text());
        } catch (Exception e) {
            log.warn("Date-check research answer for org {} is not the expected JSON", req.orgId());
            return Outcome.failed("parse", reply.usage(), model);
        }
        // Nothing found needs no source; a finding without any search result cannot be checked.
        if (!reported.isEmpty() && reply.citations().isEmpty()) {
            log.warn("Date-check research for org {} returned no search results", req.orgId());
            return Outcome.failed("no_results", reply.usage(), model);
        }
        if (!reply.citations().isEmpty()
                && reply.citations().stream().allMatch(c -> c.content() == null || c.content().isBlank())) {
            log.warn("Date-check research citations carry no excerpt; every finding will be dropped");
        }

        List<Cited> cited = reply.citations().stream().map(c -> new Cited(c.url(), c.title(), c.content())).toList();
        FindingValidator.Result checked = FindingValidator.check(reported, cited, req.city());
        log.info("Date-check research for org {}: {} reported, {} kept, dropped {}", req.orgId(), reported.size(),
                checked.kept().size(), checked.dropped());
        cache.put(key, new ResearchCache.Entry(checked.kept(), now));
        return new Outcome(true, assign(checked.kept(), req.dates(), now), reply.usage(), model, null);
    }

    private Map<LocalDate, List<Finding>> assign(List<FindingValidator.Checked> items, List<LocalDate> dates,
                                                 Instant fetchedAt) {
        Map<LocalDate, List<Finding>> out = new LinkedHashMap<>();
        for (LocalDate d : dates) out.put(d, FindingValidator.assign(items, d, bank, fetchedAt));
        return out;
    }

    /** {@code {"findings":[...]}}, tolerating a markdown fence or prose around the object. */
    static List<Reported> parse(String text) throws Exception {
        String s = text.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b < a) throw new IllegalArgumentException("no JSON object");
        JsonNode findings = JSON.readTree(s.substring(a, b + 1)).path("findings");
        if (!findings.isArray()) throw new IllegalArgumentException("no findings array");
        List<Reported> out = new ArrayList<>();
        for (JsonNode f : findings) {
            out.add(new Reported(str(f, "title"), str(f, "url"), str(f, "quote"), str(f, "type"),
                    f.path("strength").isInt() ? f.path("strength").asInt() : null));
        }
        return out;
    }

    private static String str(JsonNode n, String field) {
        return n.path(field).isTextual() ? n.path(field).asText() : null;
    }
}
