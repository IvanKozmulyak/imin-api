package com.imin.iminapi.repository;

import com.imin.iminapi.model.OrderRecoveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface OrderRecoveryAttemptRepository extends JpaRepository<OrderRecoveryAttempt, UUID> {
    long countByEmailAndAttemptedAtAfter(String email, Instant cutoff);
    long countByIpHashAndAttemptedAtAfter(String ipHash, Instant cutoff);

    /**
     * Retention sweep. Each row holds a buyer's address in the clear next to a
     * hashed IP, and the counter's window is one hour — nothing reads a row past
     * that. Mirrors {@code BuyerVerificationAttemptRepository.deleteOlderThan}.
     */
    @Transactional
    @Modifying
    @Query("delete from OrderRecoveryAttempt a where a.attemptedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
