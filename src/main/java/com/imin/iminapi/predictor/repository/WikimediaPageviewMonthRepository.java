package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.WikimediaPageviewMonth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Stored monthly pageviews per (project, article). */
@RepositoryRestResource(exported = false)
public interface WikimediaPageviewMonthRepository extends JpaRepository<WikimediaPageviewMonth, UUID> {

    /** The newest 24 months stored for the article, newest first. */
    List<WikimediaPageviewMonth> findTop24ByProjectAndArticleOrderByViewMonthDesc(String project, String article);

    List<WikimediaPageviewMonth> findByProjectAndArticleAndViewMonthIn(String project, String article,
                                                                       Collection<LocalDate> months);

    /** Newest {@code synced_at} among the stored months; null when none. */
    @Query("select max(w.syncedAt) from WikimediaPageviewMonth w")
    Instant findLatestSyncedAt();
}
