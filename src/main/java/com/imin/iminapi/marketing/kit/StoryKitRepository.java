package com.imin.iminapi.marketing.kit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface StoryKitRepository extends JpaRepository<StoryKit, UUID> {

    Optional<StoryKit> findByEventId(UUID eventId);

    /** Saves the caption only while the kit is still at {@code revision}; 0 rows means a remake won. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StoryKit k set k.caption = :caption, k.captionAiGenerated = :ai, k.captionCreatedAt = :at
             where k.id = :id and k.revision = :revision
            """)
    int updateCaption(@Param("id") UUID id, @Param("revision") int revision, @Param("caption") String caption,
                      @Param("ai") boolean ai, @Param("at") Instant at);

    /** Remake: replaces the images and snapshots, clears the caption and moves the revision, only from {@code revision}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StoryKit k set k.locale = :locale, k.assets = :assets, k.skipped = :skipped,
                   k.aiDerived = :aiDerived, k.sourcePosterUrl = :poster, k.sourceStartsAt = :startsAt,
                   k.sourceTimezone = :tz, k.sourceVenueName = :venueName, k.sourceVenueCity = :venueCity,
                   k.caption = null, k.captionAiGenerated = null, k.captionCreatedAt = null,
                   k.revision = k.revision + 1, k.createdBy = :by, k.createdAt = :at
             where k.id = :id and k.revision = :revision
            """)
    int replace(@Param("id") UUID id, @Param("revision") int revision, @Param("locale") String locale,
                @Param("assets") String assets, @Param("skipped") String skipped,
                @Param("aiDerived") boolean aiDerived, @Param("poster") String poster,
                @Param("startsAt") Instant startsAt, @Param("tz") String tz,
                @Param("venueName") String venueName, @Param("venueCity") String venueCity,
                @Param("by") UUID by, @Param("at") Instant at);
}
