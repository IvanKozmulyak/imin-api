package com.imin.iminapi.audience.repository;

import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.MarketingOptOutId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

/**
 * Sticky marketing opt-outs, keyed by address rather than by org — like
 * {@link ConsumerRepository}, this is one of the deliberately cross-tenant
 * tables, so every caller must scope it itself.
 *
 * <p>Written by {@code MarketingOptOutRecorder} on a {@code DATA_SUBJECT}
 * unsubscribe; read by the Release-2 account-level "organizer updates" toggle,
 * which may never re-subscribe an address that holds a row here (§6.3).
 */
@RepositoryRestResource(exported = false)
public interface MarketingOptOutRepository extends JpaRepository<MarketingOptOut, MarketingOptOutId> {

    /** Every organizer this address has explicitly opted out of, across all orgs. */
    List<MarketingOptOut> findByEmailNormalized(String emailNormalized);

    List<MarketingOptOut> findByEmailNormalizedAndOrgId(String emailNormalized, UUID orgId);
}
