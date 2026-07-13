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
        } catch (ResendException ex) {
            log.error("Resend batch send failed ({} emails): {}", emails.size(), ex.getMessage(), ex);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Email service unavailable", ex);
        }
    }
}
