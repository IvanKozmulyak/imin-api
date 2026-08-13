package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerSavedEvent;
import com.imin.iminapi.buyer.model.BuyerSavedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface BuyerSavedEventRepository extends JpaRepository<BuyerSavedEvent, BuyerSavedEventId> {

    /** The buyer's saved list, newest first — the order the account page renders. */
    List<BuyerSavedEvent> findByBuyerAccountIdOrderByCreatedAtDesc(UUID buyerAccountId);

    @Transactional
    void deleteByBuyerAccountIdAndEventId(UUID buyerAccountId, UUID eventId);
}
