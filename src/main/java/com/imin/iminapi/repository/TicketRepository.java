package com.imin.iminapi.repository;

import com.imin.iminapi.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    Optional<Ticket> findByToken(String token);
    List<Ticket> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /**
     * Atomic single-use redemption. Returns the number of rows updated:
     * 1 → fresh redemption; 0 → either already redeemed, revoked, or token unknown.
     * The caller selects the row again to disambiguate.
     */
    @Modifying
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
