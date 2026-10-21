package com.imin.iminapi.checkoutreminder.repository;

import com.imin.iminapi.checkoutreminder.model.OrgCheckoutReminderSetting;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The organizer's reminder switch; a missing row means off. */
@RepositoryRestResource(exported = false)
public interface OrgCheckoutReminderSettingRepository extends Repository<OrgCheckoutReminderSetting, UUID> {

    Optional<OrgCheckoutReminderSetting> findById(UUID orgId);

    default boolean isEnabled(UUID orgId) {
        return orgId != null && findById(orgId).map(OrgCheckoutReminderSetting::isEnabled).orElse(false);
    }

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO org_checkout_reminder_settings (org_id, enabled, enabled_at, enabled_by, updated_at)
            VALUES (:orgId, TRUE, :now, :userId, :now)
            ON CONFLICT (org_id) DO UPDATE SET enabled = TRUE, enabled_at = :now, enabled_by = :userId,
                                               updated_at = :now
            """, nativeQuery = true)
    int enable(@Param("orgId") UUID orgId, @Param("userId") UUID userId, @Param("now") Instant now);

    /** Keeps the last turn-on time and person for the record. */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO org_checkout_reminder_settings (org_id, enabled, updated_at)
            VALUES (:orgId, FALSE, :now)
            ON CONFLICT (org_id) DO UPDATE SET enabled = FALSE, updated_at = :now
            """, nativeQuery = true)
    int disable(@Param("orgId") UUID orgId, @Param("now") Instant now);
}
