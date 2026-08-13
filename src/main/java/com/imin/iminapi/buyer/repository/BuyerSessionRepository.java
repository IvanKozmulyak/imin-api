package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerSession;
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

/**
 * {@code @RepositoryRestResource(exported = false)} is not optional anywhere in
 * this tree: Spring Data REST is on the classpath with {@code base-path:
 * /api/v1} and no detection-strategy override, so an unguarded repository is
 * auto-published under a path that {@code SecurityConfig} only requires
 * {@code .authenticated()} on — no tenant check, no buyer check.
 * {@code RepositoryExportGuardTest} fails the build if any repository loses it.
 */
@RepositoryRestResource(exported = false)
public interface BuyerSessionRepository extends JpaRepository<BuyerSession, UUID> {

    Optional<BuyerSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    List<BuyerSession> findByBuyerAccountIdAndRevokedAtIsNull(UUID buyerAccountId);

    /** Revokes every live session for an account (logout-everywhere, credential change, deletion). */
    @Transactional
    @Modifying
    @Query("update BuyerSession s set s.revokedAt = :now " +
           "where s.buyerAccountId = :accountId and s.revokedAt is null")
    int revokeAllForAccount(@Param("accountId") UUID accountId, @Param("now") Instant now);

    /**
     * Revokes every live session for an account <b>except</b> one.
     *
     * <p>The password-change case. Distinct from {@link #revokeAllForAccount}:
     * changing a password is hygiene you perform mid-session, and signing the
     * buyer out of the very tab they did it in reads as a failure rather than
     * as protection.
     */
    @Transactional
    @Modifying
    @Query("update BuyerSession s set s.revokedAt = :now " +
           "where s.buyerAccountId = :accountId and s.id <> :keepSessionId and s.revokedAt is null")
    int revokeAllForAccountExcept(@Param("accountId") UUID accountId,
                                  @Param("keepSessionId") UUID keepSessionId,
                                  @Param("now") Instant now);

    /** Revokes one session by id. Idempotent — a second call matches nothing. */
    @Transactional
    @Modifying
    @Query("update BuyerSession s set s.revokedAt = :now " +
           "where s.id = :sessionId and s.revokedAt is null")
    int revokeById(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    /**
     * Revokes by credential rather than by id, because {@code POST /buyer/auth/logout}
     * is permit-listed and must work for a session that has already expired —
     * at which point there is no principal to read a session id from.
     */
    @Transactional
    @Modifying
    @Query("update BuyerSession s set s.revokedAt = :now " +
           "where s.tokenHash = :tokenHash and s.revokedAt is null")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /**
     * Targeted one-column write used by the auth filter. Deliberately not
     * {@code save(entity)}: the filter runs outside any business transaction
     * and must not write back a whole detached row.
     */
    @Transactional
    @Modifying
    @Query("update BuyerSession s set s.lastUsedAt = :now where s.id = :sessionId")
    int touchLastUsed(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("delete from BuyerSession s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    @Transactional
    @Modifying
    @Query("delete from BuyerSession s where s.revokedAt is not null and s.revokedAt < :cutoff")
    int deleteRevokedBefore(@Param("cutoff") Instant cutoff);

    /**
     * §7.2 step 4 — hard-delete every session row for an account being erased.
     *
     * <p>Distinct from {@link #revokeAllForAccount}: revocation stamps
     * {@code revoked_at} and leaves the row (and its {@code user_agent}, which is
     * personal data) in place for the sweeper to collect 30 days later. An
     * account being erased has no 30 days left.
     */
    @Transactional
    @Modifying
    @Query("delete from BuyerSession s where s.buyerAccountId = :accountId")
    int deleteByBuyerAccountId(@Param("accountId") UUID accountId);
}
