package com.imin.iminapi.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Unlock rows are written only here, by conditional statements, so concurrent writers never lose an update. An
 * unlock needs an enabled perk, a LIVE referrer that still holds a valid ticket, and at least {@code threshold}
 * valid tickets on LIVE orders bought with its link. Valid = issued, legacy 'pre' or redeemed.
 */
@RepositoryRestResource(exported = false)
public interface ReferralPerkUnlockRepository extends JpaRepository<ReferralPerkUnlock, UUID> {

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO referral_perk_unlocks (order_id, event_id, label, threshold, unlocked_at)
            SELECT r.id, r.event_id, p.label, p.threshold, :now
              FROM orders r
              JOIN event_referral_perks p ON p.event_id = r.event_id AND p.enabled
              JOIN orders f ON f.referred_by_order_id = r.id AND f.test_mode = false
              JOIN tickets t ON t.order_id = f.id AND t.state IN ('issued', 'pre', 'redeemed')
             WHERE r.id = :referrerId
               AND r.test_mode = false
               AND EXISTS (SELECT 1 FROM tickets rt
                            WHERE rt.order_id = r.id AND rt.state IN ('issued', 'pre', 'redeemed'))
             GROUP BY r.id, r.event_id, p.label, p.threshold
            HAVING count(t.id) >= p.threshold
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertUnlocksForReferrer(@Param("referrerId") UUID referrerId, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO referral_perk_unlocks (order_id, event_id, label, threshold, unlocked_at)
            SELECT r.id, r.event_id, p.label, p.threshold, :now
              FROM event_referral_perks p
              JOIN orders r ON r.event_id = p.event_id AND r.test_mode = false
              JOIN orders f ON f.referred_by_order_id = r.id AND f.test_mode = false
              JOIN tickets t ON t.order_id = f.id AND t.state IN ('issued', 'pre', 'redeemed')
             WHERE p.event_id = :eventId
               AND p.enabled
               AND EXISTS (SELECT 1 FROM tickets rt
                            WHERE rt.order_id = r.id AND rt.state IN ('issued', 'pre', 'redeemed'))
             GROUP BY r.id, r.event_id, p.label, p.threshold
            HAVING count(t.id) >= p.threshold
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertUnlocksForEvent(@Param("eventId") UUID eventId, @Param("now") Instant now);

    /** 1 when this call gave the perk; 0 when it was already given or never unlocked. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE referral_perk_unlocks
               SET given_at = :now, given_via = :via, given_by_label = :byLabel
             WHERE order_id = :orderId AND event_id = :eventId AND given_at IS NULL
            """, nativeQuery = true)
    int markGiven(@Param("eventId") UUID eventId, @Param("orderId") UUID orderId, @Param("via") String via,
                  @Param("byLabel") String byLabel, @Param("now") Instant now);

    /** Back to unlocked, the state before that Mark given; 0 when the mark is gone or was made at another time. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE referral_perk_unlocks
               SET given_at = NULL, given_via = NULL, given_by_label = NULL
             WHERE order_id = :orderId AND event_id = :eventId AND given_at = :givenAt
            """, nativeQuery = true)
    int undoGiven(@Param("eventId") UUID eventId, @Param("orderId") UUID orderId, @Param("givenAt") Instant givenAt);

    long countByEventId(UUID eventId);

    long countByEventIdAndGivenAtIsNotNull(UUID eventId);
}
