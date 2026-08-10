package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.publicapi.NotifySubscriptionRequest;
import com.imin.iminapi.dto.publicapi.NotifySubscriptionResponse;
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

    @Transactional
    public NotifySubscriptionResponse subscribe(UUID eventId, NotifySubscriptionRequest request) {
        String email = normalizeAndValidate(request);

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
            if (row.getNotifiedAt() != null) {
                row.setNotifiedAt(null);
                subscriptionRepository.save(row);
            }
            return NotifySubscriptionResponse.ok();
        }

        NotifySubscription sub = new NotifySubscription();
        sub.setEventId(event.getId());
        sub.setEmail(email);
        try {
            subscriptionRepository.save(sub);
        } catch (DataIntegrityViolationException dupe) {
            // Concurrent insert lost the race — that's fine, idempotent.
            return NotifySubscriptionResponse.ok();
        }
        return NotifySubscriptionResponse.ok();
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
