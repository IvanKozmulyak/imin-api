package com.imin.iminapi.marketing.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.email.EmailProperties;
import com.resend.Resend;
import com.resend.services.domains.model.AbstractDomain;
import com.resend.services.domains.model.ListDomainsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Open and click tracking must be off on the marketing sending domain for everyone.
 * TRACKING_ON when Resend reports either on, UP when it reports both off, UNKNOWN whenever it cannot tell.
 * TRACKING_ON is ordered below UP and mapped to 200, so it never turns the root health 503.
 * Never runs at boot; a probe serves the cached verdict and refreshes it single-flight in the background.
 */
@Component("resendTrackingHealthIndicator")
@ConditionalOnEnabledHealthIndicator("resend-tracking")
public class ResendTrackingHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ResendTrackingHealthIndicator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Custom status: loud in the component and the log, but not a liveness failure. */
    public static final Status TRACKING_ON = new Status("TRACKING_ON");

    static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String DOMAINS_URL = "https://api.resend.com/domains/";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    /** Tracking flags as Resend reported them; a null flag means the response did not carry it. */
    public record TrackingState(Boolean openTracking, Boolean clickTracking) {}

    private final Resend resend;
    private final EmailProperties emailProps;
    private final MarketingEmailProperties marketingProps;

    private final Executor refresher;
    private final ExecutorService ownedRefresher;
    private final Clock clock;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    private record Cached(Health health, Instant at) {}
    private volatile Cached cached;

    @Autowired
    public ResendTrackingHealthIndicator(Resend resend, EmailProperties emailProps,
                                         MarketingEmailProperties marketingProps) {
        this(resend, emailProps, marketingProps, null, Clock.systemUTC());
    }

    /** A null refresher gets a private single daemon thread. */
    ResendTrackingHealthIndicator(Resend resend, EmailProperties emailProps,
                                  MarketingEmailProperties marketingProps, Executor refresher, Clock clock) {
        this.resend = resend;
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

    private void refreshInBackground() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            refresher.execute(() -> {
                try {
                    cached = new Cached(evaluate(), clock.instant());
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            refreshing.set(false);
        }
    }

    private Health evaluate() {
        String domain = hostOf(marketingProps.getFromAddress());
        String key = emailProps.getApiKey();
        if (key == null || key.isBlank() || domain.isBlank()) {
            return Health.unknown().withDetail("reason", "not_configured").build();
        }
        TrackingState state;
        try {
            state = fetchTracking(domain);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Missing domains scope or Resend unreachable: not evidence either way.
            log.info("Resend tracking lookup for '{}' unavailable ({})", domain, e.getClass().getSimpleName());
            return Health.unknown().withDetail("domain", domain).withDetail("reason", "unavailable").build();
        }
        if (state == null) {
            return Health.unknown().withDetail("domain", domain).withDetail("reason", "domain_not_found").build();
        }
        boolean openOn = Boolean.TRUE.equals(state.openTracking());
        boolean clickOn = Boolean.TRUE.equals(state.clickTracking());
        if (openOn || clickOn) {
            log.error("Resend tracking is ON for marketing domain '{}' (open={}, click={}); it must be off",
                    domain, state.openTracking(), state.clickTracking());
            Health.Builder on = Health.status(TRACKING_ON).withDetail("domain", domain);
            if (state.openTracking() != null) on.withDetail("openTracking", state.openTracking());
            if (state.clickTracking() != null) on.withDetail("clickTracking", state.clickTracking());
            return on.build();
        }
        if (state.openTracking() == null || state.clickTracking() == null) {
            return Health.unknown().withDetail("domain", domain).withDetail("reason", "tracking_not_reported").build();
        }
        return Health.up().withDetail("domain", domain)
                .withDetail("openTracking", false).withDetail("clickTracking", false).build();
    }

    /**
     * The only network call: find the domain by name, then read its raw JSON (the 4.1.0 SDK's
     * {@code Domain} model drops the tracking fields). Null when the domain is not in the account.
     */
    protected TrackingState fetchTracking(String domain) throws Exception {
        ListDomainsResponse list = resend.domains().list();
        String id = (list == null || list.getData() == null) ? null : list.getData().stream()
                .filter(d -> domain.equalsIgnoreCase(d.getName()))
                .map(AbstractDomain::getId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (id == null) return null;
        HttpRequest req = HttpRequest.newBuilder(URI.create(DOMAINS_URL + id))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + emailProps.getApiKey())
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
