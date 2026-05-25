package com.imin.iminapi.repository;

import com.imin.iminapi.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    Optional<Ticket> findByToken(String token);
    List<Ticket> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /**
     * Batch fetch of tickets for many orders. The caller groups by
     * {@code orderId} to avoid N+1 queries when rendering listings. Order is
     * by createdAt asc within the result set (a single sort is fine — callers
     * partition by orderId themselves).
     */
    List<Ticket> findByOrderIdInOrderByOrderIdAscCreatedAtAsc(Collection<UUID> orderIds);

    List<Ticket> findByIdInAndOrderId(Collection<UUID> ids, UUID orderId);

    long countByOrderIdAndStateNot(UUID orderId, String state);

    /**
     * Atomic single-use redemption. Returns the number of rows updated:
     * 1 → fresh redemption; 0 → either already redeemed, revoked, or token unknown.
     * The caller selects the row again to disambiguate.
     */
    /**
     * {@code clearAutomatically = true} drops the cached Ticket entity from
     * the EntityManager after the UPDATE so a subsequent {@code findByToken}
     * re-reads the row with the freshly-redeemed columns rather than returning
     * the stale pre-update view.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update Ticket t
               set t.state = 'redeemed',
                   t.redeemedAt = :now,
                   t.redeemedByUserId = :userId
             where t.token = :token
               and t.state in ('issued', 'pre')
            """)
    int redeemAtomic(@Param("token") String token,
                      @Param("userId") UUID userId,
                      @Param("now") Instant now);
}
