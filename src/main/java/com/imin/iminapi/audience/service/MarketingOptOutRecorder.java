package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.MarketingOptOutId;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes the sticky {@code marketing_optouts} row for a {@code DATA_SUBJECT}
 * unsubscribe (buyer-accounts epic §6.3).
 *
 * <p><b>This is a separate bean for one reason: the transaction boundary.</b>
 * {@link #record} runs {@link Propagation#REQUIRES_NEW}, so its writes commit or roll
 * back on their own physical transaction, and a Spring proxy only applies that when
 * the call crosses bean boundaries — a private method on {@code ConsentService} would
 * silently join the caller's transaction and give no isolation at all.
 *
 * <p>The isolation is not optional. {@code ConsentService.unsubscribe} is on the live
 * RFC 8058 one-click path and on the legally mandated SMS STOP path; recording
 * stickiness is a bonus record, never a precondition. A constraint violation raised
 * inside the caller's transaction would mark it rollback-only, and the unsubscribe
 * would then fail at commit with an {@code UnexpectedRollbackException} <i>even though
 * the caller caught the original exception</i> — the same JPA hazard
 * {@code WebhookEventDedupService} documents. {@code REQUIRES_NEW} is what makes the
 * catch in {@code ConsentService} real rather than decorative.
 *
 * <p>The catch lives in the caller, deliberately, and not in here: a violation
 * detected inside this method has already poisoned <i>this</i> transaction, so
 * swallowing it here would just trade the exception for an
 * {@code UnexpectedRollbackException} at this method's own commit. Let it out; the
 * caller is outside the boundary and can treat it as success.
 */
@Component
public class MarketingOptOutRecorder {

    private final MarketingOptOutRepository optOuts;

    public MarketingOptOutRecorder(MarketingOptOutRepository optOuts) {
        this.optOuts = optOuts;
    }

    /**
     * Record that {@code rawEmail} explicitly objected to marketing from {@code orgId}
     * on {@code channel}. Idempotent: a repeat keeps the first row untouched, because
     * the date the person first objected is the one that matters.
     *
     * <p>Read-then-insert, the idiom this repository already uses
     * ({@code AudienceOrderProjector.upsertMembership}); there is no {@code ON CONFLICT}
     * anywhere in this tree. The read wins the common case in one round trip and the
     * insert is left to raise a duplicate-key violation on the rare race, which the
     * caller treats as success — the row it wanted exists either way.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException on a lost race
     *         (the caller must treat this as success)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String rawEmail, UUID orgId, String channel, String source) {
        String email = EmailNormalizer.normalize(rawEmail);
        if (email == null || email.isBlank() || orgId == null || channel == null) return;

        if (optOuts.existsById(new MarketingOptOutId(email, orgId, channel))) return;

        // saveAndFlush, not save: the INSERT has to hit the database inside this method
        // so a collision surfaces as the DataIntegrityViolationException the caller
        // handles. Deferred to commit it would arrive as an UnexpectedRollbackException
        // from the proxy instead, which says nothing about what went wrong.
        optOuts.saveAndFlush(MarketingOptOut.of(email, orgId, channel, source));
    }
}
