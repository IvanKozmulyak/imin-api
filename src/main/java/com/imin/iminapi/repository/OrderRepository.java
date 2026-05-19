package com.imin.iminapi.repository;

import com.imin.iminapi.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByToken(String token);
    Optional<Order> findByStripePaymentIntentId(String paymentIntentId);
    Optional<Order> findByStripeSessionId(String sessionId);

    @Query("""
            select o from Order o
             where lower(o.email) = lower(:email)
               and (:eventId is null or o.eventId = :eventId)
               and o.createdAt > :cutoff
             order by o.createdAt desc
            """)
    List<Order> findRecentForRecovery(@Param("email") String email,
                                       @Param("eventId") UUID eventId,
                                       @Param("cutoff") Instant cutoff);
}
