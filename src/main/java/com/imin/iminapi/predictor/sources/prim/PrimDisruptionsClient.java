package com.imin.iminapi.predictor.sources.prim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One GET of PRIM {@code disruptions_bulk/disruptions/v2} (every current and future IDFM traffic message), the key in
 * the {@code apiKey} header, never the URL. Feed times are Paris wall-clock; they are stored as instants.
 */
public class PrimDisruptionsClient {

    private static final Logger log = LoggerFactory.getLogger(PrimDisruptionsClient.class);
    static final String PATH = "/marketplace/disruptions_bulk/disruptions/v2";
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter FEED_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    /** V175 column limits. */
    static final int MAX_ID = 64;
    static final int MAX_CODE = 32;
    static final int MAX_TITLE = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Wire values match {@code ck_transit_sync_state_status}. */
    public enum Status {
        OK("ok"), FAILED("failed"), UNUSABLE("unusable"), REJECTED_KEY("rejected_key"), RATE_LIMITED("rate_limited");

        private final String wire;

        Status(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** {@code level}: {@code line} when the whole line is impacted, {@code stop} for a stop point on it. */
    public record LineRef(String ref, String label, String mode, String level) {}

    public record Period(Instant begin, Instant end) {}

    public record Disruption(String id, String cause, String severity, String kind, String title, Instant lastUpdate,
                             List<LineRef> lines, List<Period> periods) {
        public Disruption {
            lines = List.copyOf(lines);
            periods = List.copyOf(periods);
        }
    }

    /** {@code dropped}: messages without a usable id or period, or repeating an earlier id. */
    public record Snapshot(Instant feedUpdatedAt, List<Disruption> disruptions, int dropped) {
        public Snapshot {
            disruptions = List.copyOf(disruptions);
        }
    }

    /** {@code snapshot} is set only when {@code status} is OK. */
    public record Outcome(Status status, Snapshot snapshot) {
        static Outcome of(Status status) {
            return new Outcome(status, null);
        }
    }

    private final RestClient http;
    private final PrimProperties props;

    public PrimDisruptionsClient(RestClient.Builder builder, PrimProperties props) {
        this.http = builder.build();
        this.props = props;
    }

    /** Never throws: transport errors are FAILED, a body without a disruptions or lines array is UNUSABLE. */
    public Outcome fetch() {
        try {
            return http.get().uri(props.getBaseUrl() + PATH).header("apiKey", props.getApiKey()).exchange((req, res) -> {
                int status = res.getStatusCode().value();
                String remaining = res.getHeaders().getFirst("x-ratelimit-remaining-day");
                log.info("PrimDisruptionsClient: HTTP {}, daily quota remaining {}", status,
                        remaining == null ? "unknown" : remaining);
                if (status == 401) return Outcome.of(Status.REJECTED_KEY);
                if (status == 429) return Outcome.of(Status.RATE_LIMITED);
                if (!res.getStatusCode().is2xxSuccessful()) return Outcome.of(Status.FAILED);
                JsonNode root;
                try {
                    root = JSON.readTree(res.getBody());
                } catch (IOException e) {
                    log.warn("PrimDisruptionsClient: body is not JSON ({})", e.getClass().getSimpleName());
                    return Outcome.of(Status.UNUSABLE);
                }
                // without lines every message would look line-less, and every check clear
                if (root == null || !root.path("disruptions").isArray() || !root.path("lines").isArray()) {
                    return Outcome.of(Status.UNUSABLE);
                }
                return new Outcome(Status.OK, parse(root));
            }, true);
        } catch (RestClientException e) {
            log.warn("PrimDisruptionsClient: request failed ({})", e.getClass().getSimpleName());
            return Outcome.of(Status.FAILED);
        }
    }

    static Snapshot parse(JsonNode root) {
        Map<String, Set<LineRef>> linesById = new HashMap<>();
        for (JsonNode line : root.path("lines")) {
            String mode = line.path("mode").asText("");
            String ref = line.path("id").asText("");
            var label = PrimClassifier.label(mode, line.path("shortName").asText(null));
            if (label.isEmpty() || ref.isBlank()) continue;
            for (JsonNode obj : line.path("impactedObjects")) {
                String level = switch (obj.path("type").asText("")) {
                    case "line" -> "line";
                    case "stop_point" -> "stop";
                    default -> null;
                };
                if (level == null) continue;
                for (JsonNode id : obj.path("disruptionIds")) {
                    linesById.computeIfAbsent(id.asText(), k -> new LinkedHashSet<>())
                            .add(new LineRef(ref, label.get(), mode, level));
                }
            }
        }
        List<Disruption> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int dropped = 0;
        for (JsonNode d : root.path("disruptions")) {
            String id = d.path("id").asText("").strip();
            List<Period> periods = periods(d.path("applicationPeriods"));
            if (id.isEmpty() || id.length() > MAX_ID || periods.isEmpty()) {
                dropped++;
                continue;
            }
            if (!seen.add(id)) {
                dropped++;
                continue;
            }
            String cause = text(d, "cause", MAX_CODE);
            String title = text(d, "title", MAX_TITLE);
            out.add(new Disruption(id, cause, text(d, "severity", MAX_CODE), PrimClassifier.kind(cause, title), title,
                    feedTime(d.path("lastUpdate").asText("")),
                    List.copyOf(linesById.getOrDefault(id, Set.of())), periods));
        }
        return new Snapshot(instant(root.path("lastUpdatedDate").asText("")), out, dropped);
    }

    /** Periods that parse and end after they begin; others are dropped. */
    private static List<Period> periods(JsonNode raw) {
        List<Period> out = new ArrayList<>();
        for (JsonNode p : raw) {
            Instant begin = feedTime(p.path("begin").asText(""));
            Instant end = feedTime(p.path("end").asText(""));
            if (begin != null && end != null && end.isAfter(begin)) out.add(new Period(begin, end));
        }
        return out;
    }

    /** {@code yyyyMMdd'T'HHmmss} read as Europe/Paris wall-clock; null when it does not parse. */
    static Instant feedTime(String s) {
        try {
            return LocalDateTime.parse(s, FEED_TIME).atZone(PARIS).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instant(String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode d, String key, int max) {
        String s = d.path(key).asText(null);
        if (s == null) return null;
        s = s.strip();
        if (s.isEmpty()) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
