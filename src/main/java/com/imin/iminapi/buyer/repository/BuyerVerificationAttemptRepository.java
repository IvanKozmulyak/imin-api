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

    /**
     * Failed-attempt count for one address <b>from one caller</b> inside a
     * window — the §2.2 lockout input (R1.2).
     *
     * <p>The IP is half the key on purpose (V121). Counting by address alone
     * made the lockout an attack: an unauthenticated stranger could burn the
     * owner's budget with wrong codes and hold them out of verifying. Both
     * parameters are always non-null — the recorder substitutes a sentinel for
     * a missing IP — so this stays plain equality.
     */
    long countByEmailNormalizedAndClientIpAndSucceededFalseAndAttemptedAtAfter(
            String emailNormalized, String clientIp, Instant since);

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
