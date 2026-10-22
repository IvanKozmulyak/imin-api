package com.imin.iminapi.marketing.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.ai.provenance.AiEmailDisclosure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Bulk campaign email via Resend's batch endpoint (spec §2.5), up to 100 emails per call, posted directly because
 * resend-java cannot send the two headers this needs: {@code Idempotency-Key} (a replay with the same key returns the
 * original response instead of mailing again, for 24 h) and {@code x-batch-validation: permissive} (an address Resend
 * refuses fails only its own row). Every email carries the RFC 8058 List-Unsubscribe headers, the AI marker headers,
 * the Reply-To when one is set and the {@code imin_campaign} tag the webhook matches late events by.
 */
@Service
public class CampaignEmailProvider {

    private static final Logger log = LoggerFactory.getLogger(CampaignEmailProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_BATCH = 100;
    /** Tag name on every campaign email; the value is the campaign id. */
    public static final String CAMPAIGN_TAG = "imin_campaign";

    private final RestClient http;
    private final MarketingEmailProperties props;
    private final EmailProperties emailProps;

    public CampaignEmailProvider(@Qualifier(MarketingResendConfig.BEAN) RestClient http,
                                 MarketingEmailProperties props, EmailProperties emailProps) {
        this.http = http;
        this.props = props;
        this.emailProps = emailProps;
    }

    /**
     * One outgoing email; unsubscribeUrl seeds the RFC 8058 headers, {@code ai} the Art.50(2) marker headers,
     * {@code replyTo} the Reply-To (null = none) and {@code campaignId} the {@code imin_campaign} tag (null = none).
     */
    public record OutgoingEmail(String from, String to, String subject, String html, String text,
                                String unsubscribeUrl, AiEmailDisclosure ai, String replyTo, UUID campaignId) {
        public OutgoingEmail(String from, String to, String subject,
                             String html, String text, String unsubscribeUrl) {
            this(from, to, subject, html, text, unsubscribeUrl, AiEmailDisclosure.NONE, null, null);
        }

        public OutgoingEmail(String from, String to, String subject, String html, String text,
                             String unsubscribeUrl, AiEmailDisclosure ai) {
            this(from, to, subject, html, text, unsubscribeUrl, ai, null, null);
        }
    }

    /**
     * What Resend answered: {@code ids} by input index (null where the row was refused or no id came back), the
     * refusal message of every refused row by input index, and the rows the answer does not account for at all.
     */
    public record BatchResult(List<String> ids, Map<Integer, String> rowErrors, java.util.Set<Integer> unaccounted) {
        public BatchResult {
            ids = ids == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(ids));
            rowErrors = rowErrors == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(rowErrors));
            unaccounted = unaccounted == null ? java.util.Set.of() : java.util.Set.copyOf(unaccounted);
        }

        public BatchResult(List<String> ids, Map<Integer, String> rowErrors) {
            this(ids, rowErrors, java.util.Set.of());
        }

        /** Every row accepted, ids in input order. */
        public static BatchResult accepted(List<String> ids) {
            return new BatchResult(ids, Map.of());
        }

        /**
         * True when the answer neither refuses row {@code index} nor lists it: whether it was sent is unknown. A row past
         * the end of {@code ids} is unaccounted too.
         */
        public boolean unconfirmed(int index) {
            return !rejected(index) && (unaccounted.contains(index) || index >= ids.size());
        }

        /** The provider id of row {@code index}, or null when none came back for it. */
        public String idAt(int index) {
            return index < ids.size() ? ids.get(index) : null;
        }

        public boolean rejected(int index) {
            return rowErrors.containsKey(index);
        }
    }

    /**
     * Sends the batch. {@code idempotencyKey} may be null (test sends, reminders): the call is then not replay-safe.
     *
     * @throws RetryableBatchFailure the outcome is unknown or Resend asked to come back: transport failure, 5xx,
     *                               429, or the same key still in flight
     * @throws CampaignRejected      Resend refused the whole request (bad key, unverified domain, invalid request)
     * @throws ReplayMismatch        this key was already used with a different body: the first request landed
     */
    public BatchResult sendBatch(String idempotencyKey, List<OutgoingEmail> emails) {
        if (emails.size() > MAX_BATCH) {
            throw new IllegalArgumentException(
                    "Campaign batch exceeds Resend limit of " + MAX_BATCH + ": " + emails.size());
        }
        String body = body(emails);
        Response response;
        try {
            response = http.post().uri("/emails/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        h.setBearerAuth(props.resendApiKey(emailProps));
                        h.set("x-batch-validation", "permissive");
                        if (idempotencyKey != null) h.set("Idempotency-Key", idempotencyKey);
                    })
                    .body(body)
                    .exchangeForRequiredValue((req, res) -> new Response(res.getStatusCode().value(),
                            StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8),
                            res.getHeaders().getFirst("retry-after")), false);
        } catch (RuntimeException transport) {
            log.error("Resend batch send failed on transport ({} emails): {}", emails.size(), transport.getMessage());
            throw new RetryableBatchFailure("Email service unavailable", 0, true, null, transport);
        }
        return classify(response, emails.size());
    }

    private record Response(int status, String body, String retryAfter) {}

    static BatchResult classify(Response r, int size) {
        if (r.status() >= 200 && r.status() < 300) return parse(r.body(), size);
        String name = errorName(r.body());
        if (r.status() == 409 && "invalid_idempotent_request".equals(name)) {
            log.warn("Resend refused a replay with a different body ({} emails): the first request landed", size);
            throw new ReplayMismatch();
        }
        if (r.status() == 429 || r.status() >= 500 || r.status() == 409) {
            log.error("Resend batch send failed with {} {} ({} emails)", r.status(), name, size);
            throw new RetryableBatchFailure("Email service unavailable", r.status(), false,
                    r.status() == 429 ? retryAfter(r.retryAfter()) : null, null);
        }
        log.error("Resend rejected the batch with {} {} ({} emails)", r.status(), name, size);
        throw new CampaignRejected("Email service rejected the batch", r.status());
    }

    /**
     * Ids by input index. With permissive validation {@code data} lists the accepted emails in input order with the
     * refused indices left out, so accepted row i takes the next id (Resend's documented shape, pending a live check).
     * When the count does not add up, every row not refused is unaccounted: no id is guessed, because a shifted id would
     * pin another fan's bounce or complaint on this row, and the row is not recorded as sent either.
     */
    private static BatchResult parse(String body, int size) {
        JsonNode root;
        try {
            root = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            log.warn("Resend answered 2xx with an unreadable body — {} rows are unaccounted", size);
            return BatchResult.accepted(List.of());
        }
        Map<Integer, String> errors = new LinkedHashMap<>();
        for (JsonNode e : root.path("errors")) {
            JsonNode index = e.path("index");
            if (index.canConvertToInt() && index.asInt() >= 0 && index.asInt() < size) {
                errors.put(index.asInt(), e.path("message").asText("rejected"));
            }
        }
        List<String> data = new ArrayList<>();
        for (JsonNode d : root.path("data")) data.add(d.path("id").asText(null));
        List<String> ids = new ArrayList<>(Collections.nCopies(size, null));
        java.util.Set<Integer> unaccounted = new java.util.HashSet<>();
        if (data.size() == size - errors.size()) {
            int next = 0;
            for (int i = 0; i < size; i++) {
                if (!errors.containsKey(i)) ids.set(i, data.get(next++));
            }
        } else {
            log.warn("Resend returned {} ids for {} accepted emails — those rows are unaccounted", data.size(),
                    size - errors.size());
            for (int i = 0; i < size; i++) {
                if (!errors.containsKey(i)) unaccounted.add(i);
            }
        }
        return new BatchResult(ids, errors, unaccounted);
    }

    private static String body(List<OutgoingEmail> emails) {
        ArrayNode arr = JSON.createArrayNode();
        for (OutgoingEmail e : emails) {
            ObjectNode o = arr.addObject();
            o.put("from", e.from());
            o.putArray("to").add(e.to());
            o.put("subject", e.subject());
            o.put("html", e.html());
            o.put("text", e.text());
            if (e.replyTo() != null && !e.replyTo().isBlank()) o.put("reply_to", e.replyTo());
            ObjectNode headers = o.putObject("headers");
            headers(e).forEach(headers::put);
            if (e.campaignId() != null) {
                o.putArray("tags").addObject().put("name", CAMPAIGN_TAG).put("value", e.campaignId().toString());
            }
        }
        return arr.toString();
    }

    static Map<String, String> headers(OutgoingEmail e) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("List-Unsubscribe", "<" + e.unsubscribeUrl() + ">");
        h.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        if (e.ai() != null) h.putAll(e.ai().headers());
        return h;
    }

    private static String errorName(String body) {
        try {
            return JSON.readTree(body == null || body.isBlank() ? "{}" : body).path("name").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Seconds form only (Resend sends seconds); anything else is ignored. */
    private static Duration retryAfter(String header) {
        try {
            return header == null ? null : Duration.ofSeconds(Math.max(0, Long.parseLong(header.trim())));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The outcome is unknown or Resend asked to come back later; the sender keeps the key and replays it.
     * {@code transport} is true when no HTTP status came back at all.
     */
    public static class RetryableBatchFailure extends ApiException {
        private final int upstreamStatus;
        private final boolean transport;
        private final Duration retryAfter;

        public RetryableBatchFailure(String message, int upstreamStatus, boolean transport, Duration retryAfter,
                                     Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE, message, cause);
            this.upstreamStatus = upstreamStatus;
            this.transport = transport;
            this.retryAfter = retryAfter;
        }

        public int upstreamStatus() { return upstreamStatus; }
        public boolean transport() { return transport; }
        public Duration retryAfter() { return retryAfter; }
    }

    /** Resend refused the whole request; it will refuse it again until IMIN fixes the key, domain or payload. */
    public static class CampaignRejected extends ApiException {
        private final int upstreamStatus;

        public CampaignRejected(String message, int upstreamStatus) {
            super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE, message);
            this.upstreamStatus = upstreamStatus;
        }

        public int upstreamStatus() { return upstreamStatus; }
    }

    /** The key was already used with a different body, so the first request with it was delivered. */
    public static class ReplayMismatch extends ApiException {
        public ReplayMismatch() {
            super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Email service saw this batch before with a different body");
        }
    }
}
