package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.publicapi.NotifySubscriptionRequest;
import com.imin.iminapi.dto.publicapi.NotifySubscriptionResponse;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Captures a "notify me when tickets release" subscription for a public event.
 *
 * <p>The (event_id, email) pair is unique per subscription. Email is lowercased
 * server-side before insert, so case-variants of the same address (e.g.
 * "Ada@example.com" vs "ada@example.com") collapse onto one row. Duplicate inserts
 * are swallowed at the database level and surfaced as a successful idempotent 200.
 *
 * <p>Email delivery is <strong>not</strong> wired here — the subscription is
 * persisted for {@link NotifyReleaseSender} to consume. Re-subscribing an address
 * that has already been notified re-arms the row ({@code notifiedAt} back to null)
 * so the next release reaches it.
 */
@Service
public class NotifySubscriptionService {

    /** Email field length cap; matches the DB column (RFC 5321 effective limit). */
    private static final int EMAIL_MAX_LENGTH = 254;

    /**
     * The verbatim promise the buyer is shown next to the notify-me form on the public
     * event page ({@code imin-public} event-detail). Stored on every subscription as
     * proof-of-consent: if the copy changes later, existing rows keep the text their
     * owner actually agreed to. Keep this string byte-identical to the UI.
     */
    public static final String CONSENT_TEXT = "We'll email you if tickets release.";

    /** Column widths from V77 — untrusted header input is truncated, never rejected. */
    private static final int SOURCE_IP_MAX_LENGTH = 45;
    private static final int USER_AGENT_MAX_LENGTH = 255;

    private final EventRepository eventRepository;
    private final NotifySubscriptionRepository subscriptionRepository;
    private final Validator validator;

    public NotifySubscriptionService(EventRepository eventRepository,
                                     NotifySubscriptionRepository subscriptionRepository) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        // One-off Validator; we only need the @Email constraint applied programmatically.
        // Bean-validation in DTOs would route through FIELD_INVALID; we want INVALID_REQUEST.
        this.validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    /** Pre-V77 form — no provenance captured. Kept for callers with no HTTP context. */
    @Transactional
    public NotifySubscriptionResponse subscribe(UUID eventId, NotifySubscriptionRequest request) {
        return subscribe(eventId, request, null, null);
    }

    /**
     * @param sourceIp  client IP resolved by the controller (X-Forwarded-For aware).
     * @param userAgent raw {@code User-Agent} header. Both are untrusted and truncated
     *                  to the V77 column widths rather than rejected — a hostile header
     *                  must never cost a real buyer their subscription.
     */
    @Transactional
    public NotifySubscriptionResponse subscribe(UUID eventId, NotifySubscriptionRequest request,
                                                String sourceIp, String userAgent) {
        String email = normalizeAndValidate(request);
        // Locale is deliberately outside normalizeAndValidate: a bad locale is not a
        // request error, it just means "no preference" (⇒ English email).
        String locale = EmailLocale.normalizeOrNull(request == null ? null : request.locale());

        // Reuse the existing public-eligibility predicate so draft / private / deleted
        // events 404 with the same leak-safe envelope as the detail endpoint.
        Event event = eventRepository.findPublic(eventId)
                .orElseThrow(() -> ApiException.notFound("Event"));

        // Idempotency: a SELECT-then-INSERT race could still produce two parallel inserts,
        // so we rely on the UNIQUE constraint as the actual guarantee. The pre-check just
        // avoids generating obvious garbage in the unique-violation log.
        var existing = subscriptionRepository.findByEventIdAndEmail(event.getId(), email);
        if (existing.isPresent()) {
            // Re-arm: a buyer who was already notified about one release and comes back to
            // subscribe again wants to hear about the NEXT one. Without this the UNIQUE
            // pre-check would silently eat the request and we'd repeat the false promise.
            NotifySubscription row = existing.get();
            boolean wasNotified = row.getNotifiedAt() != null;
            if (wasNotified) {
                row.setNotifiedAt(null);
            }
            // Provenance describes the LATEST act of consent, so refresh it on every
            // re-subscribe — including the plain-duplicate case. The row we would be
            // mailing next was authorized here, now, from this client.
            applyProvenance(row, sourceIp, userAgent, locale);
            subscriptionRepository.save(row);
            return NotifySubscriptionResponse.ok();
        }

        NotifySubscription sub = new NotifySubscription();
        sub.setEventId(event.getId());
        sub.setEmail(email);
        applyProvenance(sub, sourceIp, userAgent, locale);
        try {
            subscriptionRepository.save(sub);
        } catch (DataIntegrityViolationException dupe) {
            // Concurrent insert lost the race — that's fine, idempotent.
            return NotifySubscriptionResponse.ok();
        }
        return NotifySubscriptionResponse.ok();
    }

    /**
     * Stamps the V77 consent-provenance columns. {@code consentText} is always the
     * current {@link #CONSENT_TEXT} constant — the exact wording shown at collection.
     */
    private static void applyProvenance(NotifySubscription row, String sourceIp,
                                        String userAgent, String locale) {
        row.setSourceIp(truncate(sourceIp, SOURCE_IP_MAX_LENGTH));
        row.setUserAgent(truncate(userAgent, USER_AGENT_MAX_LENGTH));
        row.setConsentText(CONSENT_TEXT);
        row.setLocale(locale);
    }

    /** Trims, caps to {@code max}, and normalizes blank to null. */
    private static String truncate(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * Validates and normalizes the request body. Returns the lowercased, trimmed email.
     * Throws {@link ApiException} with {@link ErrorCode#INVALID_REQUEST} on malformed input;
     * the {@code fields} map names the offending field per the public-API contract.
     */
    private String normalizeAndValidate(NotifySubscriptionRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        String raw = request == null ? null : request.email();
        String trimmed = raw == null ? null : raw.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            fields.put("email", "is required");
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid request body", fields);
        }
        if (trimmed.length() > EMAIL_MAX_LENGTH) {
            fields.put("email", "must be at most " + EMAIL_MAX_LENGTH + " characters");
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid request body", fields);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);

        // Use the standard jakarta.validation @Email constraint programmatically.
        var violations = validator.validate(new EmailHolder(lower));
        if (!violations.isEmpty()) {
            fields.put("email", "must be a valid email address");
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid request body", fields);
        }
        return lower;
    }

    /** Container used purely to drive {@link Email} validation against a single value. */
    private record EmailHolder(@Email String email) {}
}
