package com.imin.iminapi.marketing.email;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.batch.model.BatchEmail;
import com.resend.services.batch.model.CreateBatchEmailsResponse;
import com.resend.services.emails.model.CreateEmailOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bulk campaign email via Resend's BATCH API (spec §2.5) — up to 100 emails/call.
 * Distinct from the transactional ResendEmailService (which stays untouched). Every
 * email carries List-Unsubscribe + List-Unsubscribe-Post (RFC 8058) headers pointing
 * at the owned /api/v1/public/unsubscribe/{token} endpoint.
 */
@Service
public class CampaignEmailProvider {

    private static final Logger log = LoggerFactory.getLogger(CampaignEmailProvider.class);
    static final int MAX_BATCH = 100;

    private final Resend resend;

    public CampaignEmailProvider(Resend resend) {
        this.resend = resend;
    }

    /** One outgoing email; unsubscribeUrl seeds the RFC 8058 headers. */
    public record OutgoingEmail(String from, String to, String subject,
                                String html, String text, String unsubscribeUrl) {}

    /** Sends the batch; returns provider message ids in the SAME order as the input. */
    public List<String> sendBatch(List<OutgoingEmail> emails) {
        if (emails.size() > MAX_BATCH) {
            throw new IllegalArgumentException(
                    "Campaign batch exceeds Resend limit of " + MAX_BATCH + ": " + emails.size());
        }
        List<CreateEmailOptions> options = new ArrayList<>(emails.size());
        for (OutgoingEmail e : emails) {
            CreateEmailOptions o = CreateEmailOptions.builder()
                    .from(e.from())
                    .to(e.to())
                    .subject(e.subject())
                    .html(e.html())
                    .text(e.text())
                    .headers(Map.of(
                            "List-Unsubscribe", "<" + e.unsubscribeUrl() + ">",
                            "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"))
                    .build();
            options.add(o);
        }
        try {
            CreateBatchEmailsResponse resp = resend.batch().send(options);
            List<String> ids = new ArrayList<>(options.size());
            for (BatchEmail be : resp.getData()) {
                ids.add(be.getId());
            }
            return ids;
        } catch (Exception ex) {
            throw classify(ex, emails.size());
        }
    }

    /**
     * A provider failure this batch must NOT retry: the request will be rejected identically
     * every time (a 4xx — bad key, malformed address, rejected payload). Distinct from the
     * plain {@link ApiException} every other failure raises, which the sender backs off and
     * re-claims. Carries the upstream status purely so the log and the row say why.
     */
    public static class TerminalBatchFailure extends ApiException {
        private final int upstreamStatus;

        public TerminalBatchFailure(String message, int upstreamStatus, Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE, message, cause);
            this.upstreamStatus = upstreamStatus;
        }

        public int upstreamStatus() { return upstreamStatus; }
    }

    /**
     * mkt-edge-4: resend-java 4.1.0 never throws {@link ResendException} from
     * {@code Batch.send} — a non-2xx is a PLAIN {@code RuntimeException} whose message is
     * {@code "Failed to send batch emails: <code> <body>"} (Batch.java:38-40), and any
     * {@code IOException} is wrapped in a bare {@code RuntimeException}
     * (HttpClient.perform:63-65). Catching only ResendException therefore caught nothing real:
     * every 429/5xx/timeout escaped as an unchecked exception, rolled back the REQUIRES_NEW
     * batch transaction (losing the attempt increment AND the next_attempt_at backoff) and
     * reached CampaignDispatcher, which failed the whole campaign.
     *
     * <p>Classification: an I/O failure anywhere in the cause chain, a 429 and any 5xx are
     * retryable; any other 4xx is terminal for this batch. An unrecognised failure is
     * retryable — the per-row attempt budget bounds it, whereas failing a campaign on a shape
     * we have not seen before is unrecoverable.
     */
    private static ApiException classify(Exception ex, int size) {
        if (hasIoCause(ex)) {
            log.error("Resend batch send failed on transport ({} emails): {}", size, ex.getMessage(), ex);
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Email service unavailable", ex);
        }
        int status = upstreamStatus(ex.getMessage());
        if (status >= 400 && status < 500 && status != 429) {
            log.error("Resend rejected the batch with {} ({} emails): {}", status, size, ex.getMessage(), ex);
            return new TerminalBatchFailure("Email service rejected the batch", status, ex);
        }
        log.error("Resend batch send failed ({} emails): {}", size, ex.getMessage(), ex);
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                "Email service unavailable", ex);
    }

    private static boolean hasIoCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.io.IOException) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    /** The HTTP status out of Batch.send's message, or 0 when it carries none. */
    private static int upstreamStatus(String message) {
        if (message == null) return 0;
        java.util.regex.Matcher m = STATUS_IN_MESSAGE.matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static final java.util.regex.Pattern STATUS_IN_MESSAGE =
            java.util.regex.Pattern.compile("Failed to send batch emails:\\s*(\\d{3})\\b");
}
