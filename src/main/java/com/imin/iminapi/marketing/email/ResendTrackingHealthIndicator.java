package com.imin.iminapi.marketing.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.email.EmailProperties;
import com.resend.Resend;
import com.resend.services.domains.model.AbstractDomain;
import com.resend.services.domains.model.ListDomainsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Open and click tracking is expected on for the marketing sending domain (the host of the marketing from-address):
 * campaign stats, engagement segments and Momentum read the opens and clicks. Both flags on is UP; either flag off is
 * TRACKING_OFF, ordered below UP and mapped to 200 so it never turns the root health 503; anything unsure is UNKNOWN.
 * Never runs at boot: a probe or the hourly tick refreshes the cached verdict off-thread; each change alerts once.
 */
@Component("resendTrackingHealthIndicator")
@ConditionalOnEnabledHealthIndicator("resend-tracking")
public class ResendTrackingHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ResendTrackingHealthIndicator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Custom status: loud in the component and the log, but not a liveness failure. */
    public static final Status TRACKING_OFF = new Status("TRACKING_OFF");

    static final Duration CACHE_TTL = Duration.ofMinutes(5);
    static final String NOT_CONFIGURED = "not_configured";
    private static final String DOMAINS_URL = "https://api.resend.com/domains/";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    /** Tracking flags as Resend reported them; a null flag means the response did not carry it. */
    public record TrackingState(Boolean openTracking, Boolean clickTracking) {}

    private record DomainVerdict(Status status, String reason, Map<String, Object> details) {}

    private record AlertState(Status status, String reason) {}

    private final EmailProperties emailProps;
    private final MarketingEmailProperties marketingProps;

    private final Executor refresher;
    private final ExecutorService ownedRefresher;
    private final Clock clock;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    private record Cached(Health health, Instant at) {}
    private volatile Cached cached;
    private volatile AlertState lastAlerted;

    @Autowired
    public ResendTrackingHealthIndicator(EmailProperties emailProps, MarketingEmailProperties marketingProps) {
        this(emailProps, marketingProps, null, Clock.systemUTC());
    }

    /** A null refresher gets a private single daemon thread. */
    ResendTrackingHealthIndicator(EmailProperties emailProps, MarketingEmailProperties marketingProps,
                                  Executor refresher, Clock clock) {
        this.emailProps = emailProps;
        this.marketingProps = marketingProps;
        this.clock = clock;
        if (refresher == null) {
            this.ownedRefresher = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "resend-tracking-health");
                t.setDaemon(true);
                return t;
            });
            this.refresher = ownedRefresher;
        } else {
            this.ownedRefresher = null;
            this.refresher = refresher;
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedRefresher != null) ownedRefresher.shutdownNow();
    }

    /** Never waits on Resend: the last verdict (UNKNOWN before the first one lands), refreshed when stale. */
    @Override
    public Health health() {
        Cached c = cached;
        if (c == null || Duration.between(c.at(), clock.instant()).compareTo(CACHE_TTL) >= 0) {
            refreshInBackground();
            c = cached;
        }
        return c != null ? c.health() : Health.unknown().withDetail("reason", "pending").build();
    }

    /** Hourly per JVM, so a change alerts even when nobody probes health; read-only, so no ShedLock. */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    public void refreshTick() {
        health();
    }

    private void refreshInBackground() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            refresher.execute(() -> {
                try {
                    Health h = evaluate();
                    cached = new Cached(h, clock.instant());
                    alertOnChange(h);
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            refreshing.set(false);
        }
    }

    private Health evaluate() {
        String key = lookupKey();
        String domain = hostOf(marketingProps.getFromAddress());
        if (key.isBlank() || domain.isBlank()) {
            return Health.unknown().withDetail("reason", NOT_CONFIGURED).build();
        }
        DomainVerdict v = evaluateDomain(domain, key);
        Health.Builder b = Health.status(v.status());
        if (v.reason() != null) b.withDetail("reason", v.reason());
        return b.withDetail("domains", Map.of(domain, v.details())).build();
    }

    private DomainVerdict evaluateDomain(String domain, String key) {
        TrackingState state;
        try {
            state = fetchTracking(domain, key);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Missing domains scope or Resend unreachable: not evidence either way.
            return unknown("unavailable");
        }
        if (state == null) return unknown("domain_not_found");
        if (Boolean.FALSE.equals(state.openTracking()) || Boolean.FALSE.equals(state.clickTracking())) {
            Map<String, Object> d = details(TRACKING_OFF, null);
            if (state.openTracking() != null) d.put("openTracking", state.openTracking());
            if (state.clickTracking() != null) d.put("clickTracking", state.clickTracking());
            return new DomainVerdict(TRACKING_OFF, null, d);
        }
        if (state.openTracking() == null || state.clickTracking() == null) return unknown("tracking_not_reported");
        Map<String, Object> d = details(Status.UP, null);
        d.put("openTracking", true);
        d.put("clickTracking", true);
        return new DomainVerdict(Status.UP, null, d);
    }

    private static DomainVerdict unknown(String reason) {
        return new DomainVerdict(Status.UNKNOWN, reason, details(Status.UNKNOWN, reason));
    }

    private static Map<String, Object> details(Status status, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("status", status.getCode());
        if (reason != null) d.put("reason", reason);
        return d;
    }

    private void alertOnChange(Health h) {
        try {
            Object reason = h.getDetails().get("reason");
            AlertState now = new AlertState(h.getStatus(), reason == null ? null : reason.toString());
            if (now.equals(lastAlerted)) return;
            lastAlerted = now;
            alert(levelFor(now), messageFor(now, h));
        } catch (RuntimeException e) {
            // An alert must never break the refresh.
        }
    }

    /** Off is an ERROR because organizers would see untrue 0% open and click rates; unsure is a WARN. */
    private static Level levelFor(AlertState s) {
        if (TRACKING_OFF.equals(s.status())) return Level.ERROR;
        return Status.UP.equals(s.status()) ? Level.INFO : Level.WARN;
    }

    /** Names domains only, never an address or a key. */
    private static String messageFor(AlertState s, Health h) {
        Map<?, ?> perDomain = h.getDetails().get("domains") instanceof Map<?, ?> m ? m : Map.of();
        String checked = perDomain.isEmpty() ? "none" : String.join(", ", perDomain.keySet().stream()
                .map(Object::toString).toList());
        if (TRACKING_OFF.equals(s.status())) {
            return "Resend open/click tracking is OFF on " + checked + " (" + flags(perDomain)
                    + "); opens and clicks stop being recorded, so organizers see untrue open and click rates";
        }
        if (Status.UP.equals(s.status())) {
            return "Resend open/click tracking is on for " + checked;
        }
        if (NOT_CONFIGURED.equals(s.reason())) {
            return "Resend tracking check is not configured (needs RESEND_TRACKING_READ_KEY or RESEND_API_KEY"
                    + " and IMIN_MARKETING_FROM_ADDRESS); tracking cannot be checked";
        }
        return "Resend tracking check cannot confirm tracking is on (reason: " + s.reason()
                + "; domain: " + checked + ")";
    }

    private static String flags(Map<?, ?> perDomain) {
        return perDomain.values().stream()
                .filter(v -> v instanceof Map<?, ?>)
                .map(v -> (Map<?, ?>) v)
                .map(d -> String.join(", ", java.util.stream.Stream.of("openTracking", "clickTracking")
                        .filter(d::containsKey).map(k -> k + "=" + d.get(k)).toList()))
                .findFirst().orElse("");
    }

    /** One line per state change; ERROR reaches Sentry in prod. Never throws. */
    protected void alert(Level level, String message) {
        try {
            log.atLevel(level).log(message);
        } catch (RuntimeException e) {
            // A logging failure must not break the refresh.
        }
    }

    /** The read key when set (used only for GET /domains), else the send key. */
    private String lookupKey() {
        String read = emailProps.getResendTrackingReadKey();
        if (read != null && !read.isBlank()) return read.trim();
        String api = emailProps.getApiKey();
        return api == null ? "" : api.trim();
    }

    /**
     * The only network call: find the domain by name, then read its raw JSON (the 4.1.0 SDK's
     * {@code Domain} model drops the tracking fields). Null when the domain is not in the account.
     */
    protected TrackingState fetchTracking(String domain, String key) throws Exception {
        ListDomainsResponse list = new Resend(key).domains().list();
        String id = (list == null || list.getData() == null) ? null : list.getData().stream()
                .filter(d -> domain.equalsIgnoreCase(d.getName()))
                .map(AbstractDomain::getId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (id == null) return null;
        HttpRequest req = HttpRequest.newBuilder(URI.create(DOMAINS_URL + id))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Resend domain read failed: " + resp.statusCode());
        }
        return parseTracking(resp.body());
    }

    /** Reads top-level {@code open_tracking} / {@code click_tracking}; a missing or non-boolean field stays null. */
    static TrackingState parseTracking(String json) throws Exception {
        JsonNode root = JSON.readTree(json == null ? "{}" : json);
        return new TrackingState(bool(root.get("open_tracking")), bool(root.get("click_tracking")));
    }

    private static Boolean bool(JsonNode n) {
        return (n != null && n.isBoolean()) ? n.booleanValue() : null;
    }

    private static String hostOf(String fromAddress) {
        if (fromAddress == null) return "";
        int at = fromAddress.lastIndexOf('@');
        if (at < 0 || at == fromAddress.length() - 1) return "";
        return fromAddress.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }
}
