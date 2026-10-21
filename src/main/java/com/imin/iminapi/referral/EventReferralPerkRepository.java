package com.imin.iminapi.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface EventReferralPerkRepository extends JpaRepository<EventReferralPerk, UUID> {

    /** Sets the perk and (re)enables it; one statement, so two concurrent saves cannot interleave field by field. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO event_referral_perks (event_id, label, threshold, enabled, created_at, updated_at, updated_by)
            VALUES (:eventId, :label, :threshold, true, :now, :now, :userId)
            ON CONFLICT (event_id) DO UPDATE
               SET label = EXCLUDED.label, threshold = EXCLUDED.threshold, enabled = true,
                   updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
            """, nativeQuery = true)
    int upsert(@Param("eventId") UUID eventId, @Param("label") String label, @Param("threshold") short threshold,
               @Param("now") Instant now, @Param("userId") UUID userId);

    /** Stop new unlocks; existing unlock rows keep their snapshot. 0 when there is no enabled perk. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_referral_perks SET enabled = false, updated_at = :now, updated_by = :userId
             WHERE event_id = :eventId AND enabled
            """, nativeQuery = true)
    int disable(@Param("eventId") UUID eventId, @Param("now") Instant now, @Param("userId") UUID userId);
}
