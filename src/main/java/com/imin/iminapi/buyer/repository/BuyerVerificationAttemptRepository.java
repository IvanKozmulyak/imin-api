package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerVerificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface BuyerVerificationAttemptRepository extends JpaRepository<BuyerVerificationAttempt, UUID> {

    /** Failed-attempt count for one address inside a window — the §2.2 lockout input (R1.2). */
    long countByEmailNormalizedAndSucceededFalseAndAttemptedAtAfter(String emailNormalized, Instant since);

    @Transactional
    @Modifying
    @Query("delete from BuyerVerificationAttempt a where a.attemptedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * §7.2 step 4 — the lockout counter is keyed by <b>address</b>, not by
     * account, so no FK cascade reaches it and erasing the account would
     * otherwise leave a per-address login history behind.
     *
     * <p>Takes the whole address set at once because an account owns N of them.
     * Exact equality on a non-null collection — no {@code lower}/{@code like},
     * so the Postgres {@code lower(bytea)} null-String trap does not apply.
     */
    @Transactional
    @Modifying
    @Query("delete from BuyerVerificationAttempt a where a.emailNormalized in :emails")
    int deleteByEmailNormalizedIn(@Param("emails") Collection<String> emails);
}
