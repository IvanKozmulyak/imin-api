package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.MarketingOptOutId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sticky marketing opt-outs per (address, org, channel); cross-tenant, so callers scope it. Only MarketingOptOutRecorder
 * writes it: own unsubscribes and checkout refusals, complaints, reminder one-click. Read by consent, reminder, opt-in checks.
 */
@RepositoryRestResource(exported = false)
public interface MarketingOptOutRepository extends JpaRepository<MarketingOptOut, MarketingOptOutId> {

    /** Every organizer this address has explicitly opted out of, across all orgs. */
    List<MarketingOptOut> findByEmailNormalized(String emailNormalized);

    @Query("select distinct o.orgId from MarketingOptOut o")
    List<UUID> findDistinctOrgIds();

    long countByOrgIdAndCreatedAtBefore(UUID orgId, Instant cutoff);

    // A bulk JPQL delete: the derived deleteBy… would call delete(entity), which skips an entity whose isNew() is true.
    @Modifying
    @Transactional
    @Query("delete from MarketingOptOut o where o.orgId = :orgId and o.createdAt < :cutoff")
    int deleteByOrgIdAndCreatedAtBefore(@Param("orgId") UUID orgId, @Param("cutoff") Instant cutoff);
}
