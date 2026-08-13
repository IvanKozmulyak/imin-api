package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface BuyerAccountRepository extends JpaRepository<BuyerAccount, UUID> {

    /**
     * Accounts past their 30-day deletion grace period (§7.2). Drives
     * {@code BuyerAccountErasureJob}, and is the read
     * {@code ix_buyer_accounts_delete_due (status, delete_at)} exists for.
     *
     * <p>The {@code deleteAt is not null} guard is not redundant with the status
     * predicate: nothing at the database level ties the two columns together, and
     * a {@code delete_pending} row with a NULL date must never be read as "due
     * since the beginning of time".
     */
    @Query("select a from BuyerAccount a where a.status = 'delete_pending' " +
           "and a.deleteAt is not null and a.deleteAt <= :now order by a.deleteAt asc")
    List<BuyerAccount> findErasureDue(@Param("now") Instant now);

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
