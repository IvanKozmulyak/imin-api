package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReplicateClient {

    private static final Logger log = LoggerFactory.getLogger(ReplicateClient.class);
    private static final int MAX_THROTTLE_RETRIES = 4;
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 10L;
    private static final long MAX_RETRY_AFTER_SECONDS = 60L;
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)");

    private final RestClient replicateRestClient;

    public ReplicateClient(RestClient replicateRestClient) {
        this.replicateRestClient = replicateRestClient;
    }

    public String runAndAwaitImageUrl(String model, Map<String, Object> input) {
        Map<String, Object> body = Map.of("input", input);
        log.debug("Replicate request → model={}, inputKeys={}", model, input.keySet());

        PredictionResponse initial = submitWithThrottleRetry(model, body);

        PredictionResponse terminal = awaitTerminal(initial);

        if (!"succeeded".equals(terminal.status())) {
            throw new IllegalStateException("Replicate prediction " + terminal.status()
                    + (terminal.error() != null ? ": " + terminal.error() : ""));
        }
        return extractOutputUrl(terminal.output());
    }

    private PredictionResponse submitWithThrottleRetry(String model, Map<String, Object> body) {
        HttpClientErrorException lastThrottle = null;
        for (int attempt = 1; attempt <= MAX_THROTTLE_RETRIES; attempt++) {
            try {
                return replicateRestClient.post()
                        .uri("/v1/models/{model}/predictions", model)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Prefer", "wait=60")
                        .body(body)
                        .retrieve()
                        .body(PredictionResponse.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                lastThrottle = e;
                long waitSeconds = parseRetryAfter(e);
                if (attempt == MAX_THROTTLE_RETRIES) break;
                log.warn("Replicate 429 — attempt {}/{}, retrying in {}s",
                        attempt, MAX_THROTTLE_RETRIES, waitSeconds);
                sleep(waitSeconds);
            }
        }
        throw new IllegalStateException("Replicate throttled after " + MAX_THROTTLE_RETRIES
                + " attempts: " + (lastThrottle != null ? lastThrottle.getMessage() : "unknown"),
                lastThrottle);
    }

    private long parseRetryAfter(HttpClientErrorException e) {
        String retryAfterHeader = e.getResponseHeaders() != null
                ? e.getResponseHeaders().getFirst("Retry-After")
                : null;
        if (retryAfterHeader != null) {
            try {
                return clamp(Long.parseLong(retryAfterHeader.trim()));
            } catch (NumberFormatException ignored) { /* fall through to body parse */ }
        }
        String body = e.getResponseBodyAsString();
        if (body != null) {
            Matcher m = RETRY_AFTER_PATTERN.matcher(body);
            if (m.find()) {
                return clamp(Long.parseLong(m.group(1)));
            }
        }
        return DEFAULT_RETRY_AFTER_SECONDS;
    }

    private long clamp(long seconds) {
        if (seconds < 1L) return 1L;
        return Math.min(seconds, MAX_RETRY_AFTER_SECONDS);
    }

    private void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting out Replicate 429", ie);
        }
    }

    private PredictionResponse awaitTerminal(PredictionResponse initial) {
        PredictionResponse current = initial;
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(120).toMillis();
        while (!isTerminal(current.status())) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Replicate prediction timed out: " + current.id());
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Replicate prediction", e);
            }
            String getUrl = current.urls() != null ? current.urls().get() : null;
            if (getUrl == null) {
                throw new IllegalStateException("Replicate response missing urls.get for polling");
            }
            current = replicateRestClient.get()
                    .uri(URI.create(getUrl))
                    .retrieve()
                    .body(PredictionResponse.class);
        }
        return current;
    }

    private boolean isTerminal(String status) {
        return "succeeded".equals(status) || "failed".equals(status) || "canceled".equals(status);
    }

    private String extractOutputUrl(Object output) {
        if (output == null) {
            throw new IllegalStateException("Replicate returned no output");
        }
        if (output instanceof String s) return s;
        if (output instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) return s;
        throw new IllegalStateException("Replicate output was not a URL: " + output);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PredictionResponse(String id, String status, Object output, String error, Urls urls) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Urls(String get) {}
    }
}
