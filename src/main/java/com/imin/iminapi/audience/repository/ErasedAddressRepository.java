package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.ErasedAddress;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

/**
 * The erasure ledger (V99). Append + read only — an erasure record is never
 * edited or deleted, because the only thing it can be used for is refusing to
 * rebuild a profile.
 */
@RepositoryRestResource(exported = false)
public interface ErasedAddressRepository extends Repository<ErasedAddress, UUID> {

    ErasedAddress save(ErasedAddress entry);

    /** This org already erased this address. */
    @Query("select count(e) > 0 from ErasedAddress e "
            + "where e.emailNormalized = :email and e.orgId = :orgId")
    boolean existsForOrg(@Param("orgId") UUID orgId, @Param("email") String email);

    /** The whole account was erased — applies to every org. */
    @Query("select count(e) > 0 from ErasedAddress e "
            + "where e.emailNormalized = :email and e.orgId is null")
    boolean existsPlatformWide(@Param("email") String email);

    /**
     * The whole ledger, for the nightly backfill. Loaded once per run and matched
     * in memory: the backfill walks one (org, email) pair per buyer per org, and a
     * per-pair existence query would be one round trip each for a table that is
     * small by construction (it grows only with erasure requests).
     */
    @Query("select e from ErasedAddress e")
    List<ErasedAddress> findAllEntries();
}
