package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.Consumer;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared (platform-wide) repository for {@link Consumer}.
 * Consumers are keyed by normalized_email — no org_id on the table.
 * This is one of the two M4 allow-listed exceptions (the other being
 * deliverability suppression). Callers must normalize via EmailNormalizer.
 */
public interface ConsumerRepository extends Repository<Consumer, UUID> {

    Optional<Consumer> findByNormalizedEmail(String normalizedEmail);

    Consumer save(Consumer consumer);

    /** Batch fetch by consumerIds — used by SendGateService for email resolution. */
    @Query("select c from Consumer c where c.consumerId in :ids")
    List<Consumer> findAllByConsumerIdIn(@Param("ids") Collection<UUID> ids);

    /** How many memberships reference this consumer (across all orgs). Used by DSAR erase. */
    @Query("select count(m) from Membership m where m.consumerId = :consumerId")
    long countMembershipsByConsumerId(@Param("consumerId") UUID consumerId);

    /** Delete a consumer by id. Only called when no memberships remain (DSAR erase). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("delete from Consumer c where c.consumerId = :consumerId")
    void deleteByConsumerId(@Param("consumerId") UUID consumerId);
}
