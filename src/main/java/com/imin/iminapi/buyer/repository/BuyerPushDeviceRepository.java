package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerPushDevice;
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

/**
 * {@code exported = false} is mandatory: Spring Data REST auto-exposes every
 * repository in this project, and an exported repository would publish every
 * buyer's push tokens on an unauthenticated CRUD surface.
 */
@RepositoryRestResource(exported = false)
public interface BuyerPushDeviceRepository extends JpaRepository<BuyerPushDevice, UUID> {

    /**
     * The token is globally unique, so this is the whole ownership lookup: one
     * device, one row, whoever it currently belongs to.
     */
    Optional<BuyerPushDevice> findByExpoToken(String expoToken);

    /** Live delivery addresses for a batch of accounts. Used by the drop-alert fan-out. */
    @Query("SELECT d.expoToken FROM BuyerPushDevice d "
         + "WHERE d.revokedAt IS NULL AND d.buyerAccountId IN :accountIds")
    List<String> findLiveTokensForAccounts(@Param("accountIds") Collection<UUID> accountIds);

    /**
     * Bulk revoke, for tokens Expo has reported as {@code DeviceNotRegistered}.
     * Already-revoked rows keep their original timestamp rather than being
     * re-stamped, which is what the {@code revokedAt IS NULL} clause is for.
     *
     * <p>{@code @Transactional} here, not on the caller: a {@code @Modifying}
     * query with no transaction throws {@code TransactionRequiredException}, and
     * the eventual caller is a best-effort push fan-out that runs outside one.
     * Same placement as {@code BuyerSavedEventRepository.deleteBy…}.
     */
    @Transactional
    @Modifying
    @Query("UPDATE BuyerPushDevice d SET d.revokedAt = :now "
         + "WHERE d.revokedAt IS NULL AND d.expoToken IN :tokens")
    int revokeByTokens(@Param("tokens") Collection<String> tokens, @Param("now") Instant now);
}
