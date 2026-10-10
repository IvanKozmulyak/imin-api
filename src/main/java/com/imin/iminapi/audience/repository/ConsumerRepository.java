package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.Consumer;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
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
@RepositoryRestResource(exported = false)
public interface ConsumerRepository extends Repository<Consumer, UUID> {

    Optional<Consumer> findByNormalizedEmail(String normalizedEmail);

    /** Resolve one consumer by id — used by DSAR erase to recover the normalized email. */
    @Query("select c from Consumer c where c.consumerId = :consumerId")
    Optional<Consumer> findByConsumerId(@Param("consumerId") UUID consumerId);

    Consumer save(Consumer consumer);

    /**
     * Inserts the consumer unless one already holds the address; an existing row is left untouched.
     * A concurrent insert is waited out rather than raised, so the caller's transaction stays usable.
     * Joins the caller's transaction; some callers reach it without one.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "insert into consumers (consumer_id, normalized_email, display_name, created_at)"
            + " values (:id, :email, :displayName, :createdAt) on conflict (normalized_email) do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("email") String normalizedEmail,
                       @Param("displayName") String displayName, @Param("createdAt") java.time.Instant createdAt);

    /** Batch fetch by consumerIds — used by SendGateService for email resolution. Any number of ids. */
    default List<Consumer> findAllByConsumerIdIn(Collection<UUID> ids) {
        return com.imin.iminapi.util.IdChunks.query(ids, this::findChunkByConsumerIdIn);
    }

    /** At most {@link com.imin.iminapi.util.IdChunks#MAX_IDS_PER_QUERY} ids; callers use findAllByConsumerIdIn. */
    @Query("select c from Consumer c where c.consumerId in :ids")
    List<Consumer> findChunkByConsumerIdIn(@Param("ids") Collection<UUID> ids);

    /** How many memberships reference this consumer (across all orgs). Used by DSAR erase. */
    @Query("select count(m) from Membership m where m.consumerId = :consumerId")
    long countMembershipsByConsumerId(@Param("consumerId") UUID consumerId);

    /** Delete a consumer by id. Only called when no memberships remain (DSAR erase). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("delete from Consumer c where c.consumerId = :consumerId")
    void deleteByConsumerId(@Param("consumerId") UUID consumerId);
}
