package com.imin.iminapi.checkoutreminder.repository;

import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Unfinished-checkout reminders. Every write is a column-scoped statement with a status predicate:
 * the sender, the webhooks, unsubscribe, erasure and the purge each touch only what they own.
 * Addresses are passed already normalized ({@code EmailNormalizer}).
 */
@RepositoryRestResource(exported = false)
public interface CheckoutReminderRepository extends Repository<CheckoutReminder, UUID> {

    Optional<CheckoutReminder> findById(UUID id);

    Optional<CheckoutReminder> findByProviderMessageId(String providerMessageId);

    /**
     * Keeps one row per (night, address). A later expiry updates the row only while it still waits
     * and only with a newer checkout; a claimed, sent or dropped row is left alone. Returns rows written.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO checkout_reminders (id, org_id, event_id, tier_id, qty, stripe_session_id, email_normalized,
                                            buyer_locale, promo_used, consent_proof, consent_text_version,
                                            started_at, captured_at, due_at, status, attempts)
            VALUES (:id, :orgId, :eventId, :tierId, :qty, :sessionId, :email, :locale, :promoUsed, :proof, :version,
                    :startedAt, :capturedAt, :dueAt, 'PENDING', 0)
            ON CONFLICT (event_id, email_normalized) WHERE email_normalized IS NOT NULL
            DO UPDATE SET tier_id = EXCLUDED.tier_id, qty = EXCLUDED.qty,
                          stripe_session_id = EXCLUDED.stripe_session_id, buyer_locale = EXCLUDED.buyer_locale,
                          promo_used = EXCLUDED.promo_used, consent_proof = EXCLUDED.consent_proof,
                          consent_text_version = EXCLUDED.consent_text_version, started_at = EXCLUDED.started_at,
                          captured_at = EXCLUDED.captured_at, due_at = EXCLUDED.due_at
             WHERE checkout_reminders.status = 'PENDING'
               AND checkout_reminders.started_at <= EXCLUDED.started_at
            """, nativeQuery = true)
    int upsertWaiting(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("eventId") UUID eventId,
                      @Param("tierId") UUID tierId, @Param("qty") int qty, @Param("sessionId") String sessionId,
                      @Param("email") String email, @Param("locale") String locale,
                      @Param("promoUsed") boolean promoUsed, @Param("proof") String proof,
                      @Param("version") String version, @Param("startedAt") Instant startedAt,
                      @Param("capturedAt") Instant capturedAt, @Param("dueAt") Instant dueAt);

    /** Claims up to {@code limit} due rows of every org; another replica's claimed rows are skipped. */
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET status = 'SENDING', claimed_at = :now
             WHERE id IN (SELECT id FROM checkout_reminders
                           WHERE status = 'PENDING' AND due_at <= :now
                           ORDER BY due_at, id
                           LIMIT :limit
                           FOR UPDATE SKIP LOCKED)
            RETURNING id
            """, nativeQuery = true)
    List<UUID> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    /** A claim older than {@code cutoff} may already have reached the provider, so it is never retried. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET status = 'NOT_SENT', reason = 'SEND_UNKNOWN'
             WHERE status = 'SENDING' AND claimed_at < :cutoff
            """, nativeQuery = true)
    int markStaleSendingUnknown(@Param("cutoff") Instant cutoff);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET status = 'SENT', sent_at = :now, provider_message_id = :messageId
             WHERE id = :id AND status = 'SENDING'
            """, nativeQuery = true)
    int markSent(@Param("id") UUID id, @Param("messageId") String messageId, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET status = 'NOT_SENT', reason = :reason
             WHERE id = :id AND status = 'SENDING'
            """, nativeQuery = true)
    int markNotSent(@Param("id") UUID id, @Param("reason") String reason);

    /** Back to waiting after a transient provider failure, one attempt used. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET status = 'PENDING', attempts = attempts + 1, claimed_at = NULL
             WHERE id = :id AND status = 'SENDING'
            """, nativeQuery = true)
    int releaseForRetry(@Param("id") UUID id);

    /** Back to waiting with a later due time: the claim landed inside quiet hours. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET status = 'PENDING', due_at = :dueAt, claimed_at = NULL
             WHERE id = :id AND status = 'SENDING'
            """, nativeQuery = true)
    int reschedule(@Param("id") UUID id, @Param("dueAt") Instant dueAt);

    /** The organizer turned the reminder off: every waiting row is dropped. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET status = 'NOT_SENT', reason = 'DISABLED'
             WHERE org_id = :orgId AND status = 'PENDING'
            """, nativeQuery = true)
    int disableWaiting(@Param("orgId") UUID orgId);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET delivered_at = COALESCE(delivered_at, :at) WHERE id = :id
            """, nativeQuery = true)
    int markDelivered(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET complained_at = COALESCE(complained_at, :at) WHERE id = :id
            """, nativeQuery = true)
    int markComplained(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET unsubscribed_at = COALESCE(unsubscribed_at, :at) WHERE id = :id
            """, nativeQuery = true)
    int markUnsubscribed(@Param("id") UUID id, @Param("at") Instant at);

    /** Credits the order to a reminder for the same night and address sent in {@code [windowStart, orderAt]}. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders SET recovered_order_id = :orderId, recovered_at = :orderAt
             WHERE event_id = :eventId AND email_normalized = :email AND status = 'SENT'
               AND recovered_order_id IS NULL
               AND sent_at <= :orderAt AND sent_at >= :windowStart
            """, nativeQuery = true)
    int stampRecovered(@Param("eventId") UUID eventId, @Param("email") String email,
                       @Param("orderId") UUID orderId, @Param("orderAt") Instant orderAt,
                       @Param("windowStart") Instant windowStart);

    /** Org-scoped erasure: this org's rows for the address lose it now; a waiting row becomes ERASED. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders
               SET email_normalized = NULL, consent_proof = NULL, address_wiped_at = :now,
                   reason = CASE WHEN status = 'PENDING' THEN 'ERASED' ELSE reason END,
                   status = CASE WHEN status = 'PENDING' THEN 'NOT_SENT' ELSE status END
             WHERE org_id = :orgId AND email_normalized = :email
            """, nativeQuery = true)
    int wipeForOrg(@Param("orgId") UUID orgId, @Param("email") String email, @Param("now") Instant now);

    /** Account erasure: every org's rows for these addresses. Callers keep {@code emails} non-empty. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders
               SET email_normalized = NULL, consent_proof = NULL, address_wiped_at = :now,
                   reason = CASE WHEN status = 'PENDING' THEN 'ERASED' ELSE reason END,
                   status = CASE WHEN status = 'PENDING' THEN 'NOT_SENT' ELSE status END
             WHERE email_normalized IN (:emails)
            """, nativeQuery = true)
    int wipeForEmails(@Param("emails") Collection<String> emails, @Param("now") Instant now);

    /**
     * Retention for rows that never reached the provider: the address goes once the night started
     * before {@code nightCutoff}, or the row was captured before {@code maxCutoff}. A waiting row is
     * dropped as TOO_LATE; a claimed row is left to its sender.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders r
               SET email_normalized = NULL, consent_proof = NULL, address_wiped_at = :now,
                   reason = CASE WHEN r.status = 'PENDING' THEN 'TOO_LATE' ELSE r.reason END,
                   status = CASE WHEN r.status = 'PENDING' THEN 'NOT_SENT' ELSE r.status END
              FROM events e
             WHERE e.id = r.event_id
               AND r.email_normalized IS NOT NULL
               AND (r.status = 'PENDING' OR (r.status = 'NOT_SENT' AND r.reason <> 'SEND_UNKNOWN'))
               AND ((e.starts_at IS NOT NULL AND e.starts_at < :nightCutoff) OR r.captured_at < :maxCutoff)
            """, nativeQuery = true)
    int wipeUnsent(@Param("nightCutoff") Instant nightCutoff, @Param("maxCutoff") Instant maxCutoff,
                   @Param("now") Instant now);

    /** Retention for rows that may have reached the buyer: the proof of the tick is kept until {@code cutoff}. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE checkout_reminders
               SET email_normalized = NULL, consent_proof = NULL, address_wiped_at = :now
             WHERE email_normalized IS NOT NULL
               AND (status = 'SENT' OR (status = 'NOT_SENT' AND reason = 'SEND_UNKNOWN'))
               AND COALESCE(sent_at, claimed_at, captured_at) < :cutoff
            """, nativeQuery = true)
    int wipeSent(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM checkout_reminders WHERE captured_at < :cutoff", nativeQuery = true)
    int deleteCapturedBefore(@Param("cutoff") Instant cutoff);

    /** Rows of {@code [status, reason, count]} for the org's reminders captured since {@code since}. */
    @Query(value = """
            SELECT status, reason, count(*) FROM checkout_reminders
             WHERE org_id = :orgId AND captured_at >= :since
             GROUP BY status, reason
            """, nativeQuery = true)
    List<Object[]> countByStatusAndReason(@Param("orgId") UUID orgId, @Param("since") Instant since);

    /** Orders credited to the org's sent reminders captured since {@code since}. */
    @Query("""
            select r.recoveredOrderId from CheckoutReminder r
             where r.orgId = :orgId and r.capturedAt >= :since
               and r.status = com.imin.iminapi.checkoutreminder.model.CheckoutReminderStatus.SENT
               and r.recoveredOrderId is not null
            """)
    List<UUID> findRecoveredOrderIds(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("""
            select r from CheckoutReminder r
             where r.orgId = :orgId and r.capturedAt >= :since
             order by r.capturedAt desc, r.id desc
            """)
    List<CheckoutReminder> findRecent(@Param("orgId") UUID orgId, @Param("since") Instant since, Pageable page);

    /** The org's rows for one address, for the access export. */
    @Query("""
            select r from CheckoutReminder r
             where r.orgId = :orgId and r.emailNormalized = :email
             order by r.capturedAt asc
            """)
    List<CheckoutReminder> findForOrgAndEmail(@Param("orgId") UUID orgId, @Param("email") String email);

    /**
     * Paid checkouts of the org's nights that ran out unpaid since {@code since}: holds released with a
     * Stripe session attached, whatever released them (expiry, payment failure, the sweeper).
     */
    @Query(value = """
            SELECT count(*) FROM ticket_reservations tr
              JOIN ticket_tiers t ON t.id = tr.tier_id
              JOIN events e ON e.id = t.event_id
             WHERE e.org_id = :orgId AND tr.status = 'RELEASED'
               AND tr.stripe_session_id IS NOT NULL AND tr.released_at >= :since
            """, nativeQuery = true)
    long countUnpaidCheckouts(@Param("orgId") UUID orgId, @Param("since") Instant since);
}
