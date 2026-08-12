package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.SuppressionEntry;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Suppression repository — two separate scopes handled here.
 *
 * <p>Marketing suppression is org-scoped. Deliverability suppression is shared/allow-listed
 * (keyed on normalizedEmail, no orgId) — this is the M4 exception.
 */
@RepositoryRestResource(exported = false)
public interface SuppressionRepository extends Repository<SuppressionEntry, UUID> {

    SuppressionEntry save(SuppressionEntry entry);

    // ---- marketing (org-scoped) ----

    @Query("select s from SuppressionEntry s where s.scope = 'marketing' and s.orgId = :orgId and s.membershipId = :membershipId")
    Optional<SuppressionEntry> findMarketingByOrgAndMembership(@Param("orgId") UUID orgId,
                                                                @Param("membershipId") UUID membershipId);

    @Query("select s from SuppressionEntry s where s.scope = 'marketing' and s.orgId = :orgId")
    List<SuppressionEntry> findMarketingByOrg(@Param("orgId") UUID orgId);

    /** Batch check: which of these membership IDs are marketing-suppressed in this org? */
    @Query("select s.membershipId from SuppressionEntry s where s.scope = 'marketing' and s.orgId = :orgId and s.membershipId in :membershipIds")
    List<UUID> findMarketingSuppressedMembershipIds(@Param("orgId") UUID orgId,
                                                     @Param("membershipIds") java.util.Collection<UUID> membershipIds);

    @Modifying
    @Transactional
    @Query("delete from SuppressionEntry s where s.scope = 'marketing' and s.orgId = :orgId and s.membershipId = :membershipId")
    int deleteMarketing(@Param("orgId") UUID orgId, @Param("membershipId") UUID membershipId);

    // ---- deliverability (shared / M4 allow-listed exception) ----

    @Query("select s from SuppressionEntry s where s.scope = 'deliverability' and s.normalizedEmail = :email")
    Optional<SuppressionEntry> findDeliverabilityByEmail(@Param("email") String email);

    /** Batch check: which of these emails are deliverability-suppressed? */
    @Query("select s.normalizedEmail from SuppressionEntry s where s.scope = 'deliverability' and s.normalizedEmail in :emails")
    List<String> findDeliverabilityEmailsIn(@Param("emails") java.util.Collection<String> emails);

    // ---- DSAR cascade ----

    @Modifying
    @Transactional
    @Query("delete from SuppressionEntry s where s.scope = 'marketing' and s.orgId = :orgId and s.membershipId = :membershipId")
    int deleteMarketingByOrgAndMembership(@Param("orgId") UUID orgId, @Param("membershipId") UUID membershipId);
}
