package com.imin.iminapi.repository;

import com.imin.iminapi.model.PromoCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface PromoCodeRepository extends JpaRepository<PromoCode, UUID> {
    List<PromoCode> findByEventId(UUID eventId);

    Optional<PromoCode> findByIdAndEventId(UUID id, UUID eventId);

    Optional<PromoCode> findByEventIdAndCodeIgnoreCase(UUID eventId, String code);

    /**
     * Atomic +1 on used_count. Done as a single UPDATE rather than fetch-modify-save
     * because Stripe webhooks can fire concurrently (multiple workers, retries) and
     * a read-modify-write loop would lose increments under contention.
     *
     * {@code @Transactional} is on the repo method directly: Spring's proxy-based AOP
     * doesn't apply caller-side {@code @Transactional} to internal same-bean calls, so
     * making the method self-transactional is the most robust place for the boundary.
     */
    @Modifying
    @Transactional
    @Query("UPDATE PromoCode p SET p.usedCount = p.usedCount + 1 WHERE p.id = :id")
    int incrementUsedCount(@Param("id") UUID id);
}
