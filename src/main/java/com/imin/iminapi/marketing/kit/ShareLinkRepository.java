package com.imin.iminapi.marketing.kit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface ShareLinkRepository extends JpaRepository<ShareLink, String> {

    Optional<ShareLink> findByEventIdAndPurpose(UUID eventId, String purpose);
}
