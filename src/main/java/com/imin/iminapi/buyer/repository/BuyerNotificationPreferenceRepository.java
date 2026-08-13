package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

/**
 * Keyed by {@code buyer_account_id}. An absent row means defaults, not "no
 * preferences" — see {@link BuyerNotificationPreference}.
 */
@RepositoryRestResource(exported = false)
public interface BuyerNotificationPreferenceRepository
        extends JpaRepository<BuyerNotificationPreference, UUID> {}
