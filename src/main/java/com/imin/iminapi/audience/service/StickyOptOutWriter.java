package com.imin.iminapi.audience.service;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The sticky {@code marketing_optouts} row, written in its own transaction; a lost race is the row we
 * wanted, anything else throws so the caller answers 500 and the sender retries.
 */
@Component
public class StickyOptOutWriter {

    private static final Logger log = LoggerFactory.getLogger(StickyOptOutWriter.class);

    private final MarketingOptOutRecorder optOutRecorder;

    public StickyOptOutWriter(MarketingOptOutRecorder optOutRecorder) {
        this.optOutRecorder = optOutRecorder;
    }

    public void recordOrFail(String email, UUID orgId, String source) {
        try {
            optOutRecorder.record(email, orgId, "email", source);
            return;
        } catch (DataIntegrityViolationException violation) {
            boolean duplicate;
            try {
                duplicate = optOutRecorder.exists(email, orgId, "email");
            } catch (RuntimeException readFailure) {
                duplicate = false;
            }
            if (duplicate) return;
            logFailure(source, orgId, violation);
        } catch (RuntimeException e) {
            logFailure(source, orgId, e);
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                "The opt-out was not recorded. Try again.");
    }

    private static void logFailure(String source, UUID orgId, RuntimeException e) {
        // No throwable: its SQL detail carries the address past the redaction.
        log.error("[sticky-optout] {} opt-out not recorded for org {}: {}: {}", source, orgId,
                e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
    }
}
