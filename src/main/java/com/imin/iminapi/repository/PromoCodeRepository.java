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
     * Atomic +1 on used_count, <b>capped</b>. Done as a single UPDATE rather than
     * fetch-modify-save because Stripe webhooks can fire concurrently (multiple workers,
     * retries) and a read-modify-write loop would lose increments under contention.
     *
     * <p>The {@code usedCount < maxUses} predicate is what actually enforces the cap
     * (events-8): the caller-side {@code usedCount >= maxUses} check is a plain read, so
     * two redeemers holding the last remaining use both passed it and both incremented,
     * landing at {@code maxUses + 1}. Returns 0 when the code is gone <i>or</i> already at
     * its cap — the caller decides which of those it can distinguish and what to do.
     *
     * {@code @Transactional} is on the repo method directly: Spring's proxy-based AOP
     * doesn't apply caller-side {@code @Transactional} to internal same-bean calls, so
     * making the method self-transactional is the most robust place for the boundary.
     */
    @Modifying
    @Transactional
    @Query("UPDATE PromoCode p SET p.usedCount = p.usedCount + 1 "
            + "WHERE p.id = :id AND p.usedCount < p.maxUses")
    int incrementUsedCount(@Param("id") UUID id);
}
