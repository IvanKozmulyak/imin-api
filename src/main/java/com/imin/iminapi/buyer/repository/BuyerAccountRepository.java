package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface BuyerAccountRepository extends JpaRepository<BuyerAccount, UUID> {

    /**
     * Squatter GC (§2.3 rule 2): an account that never had a verified address
     * and whose last unverified email row has just been swept has nothing of
     * value in it. The {@code createdAt} bound keeps a just-created account
     * safe from a sweep that interleaves with its first email insert.
     */
    @Transactional
    @Modifying
    @Query("delete from BuyerAccount a where a.activatedAt is null and a.createdAt < :cutoff " +
           "and not exists (select 1 from BuyerAccountEmail e where e.buyerAccountId = a.id)")
    int deleteNeverActivatedWithoutEmails(@Param("cutoff") Instant cutoff);
}
