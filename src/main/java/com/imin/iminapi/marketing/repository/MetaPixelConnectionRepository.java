package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.MetaPixelConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MetaPixelConnectionRepository extends JpaRepository<MetaPixelConnection, UUID> {

    /** The org-wide default connection (event_id IS NULL). */
    Optional<MetaPixelConnection> findByOrgIdAndEventIdIsNull(UUID orgId);

    /** An event-specific connection override, if one exists. */
    Optional<MetaPixelConnection> findByOrgIdAndEventId(UUID orgId, UUID eventId);

    /**
     * The connection owning a specific pixel for this org, regardless of scope
     * (event-override or org-wide). Used by MetaCapiPoller to decrypt the token for a
     * row whose pixel_id was chosen at write time — so an event-scoped-only pixel (no
     * org-wide default) still resolves. See Task 8 resolveConnection().
     */
    Optional<MetaPixelConnection> findByOrgIdAndPixelId(UUID orgId, String pixelId);
}
