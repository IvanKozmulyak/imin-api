package com.imin.iminapi.repository;

import com.imin.iminapi.model.FunnelEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface FunnelEventRepository extends JpaRepository<FunnelEvent, UUID> {

    /**
     * Distinct-session counts per stage for an event. Tuple shape:
     * {@code [String stage, Long distinctAnonCount]}. Stages with zero rows are
     * simply absent from the result — the caller defaults them to 0.
     */
    @Query("""
            select e.stage, count(distinct e.anonId) from FunnelEvent e
             where e.eventId = :eventId
             group by e.stage
            """)
    List<Object[]> countDistinctAnonByStage(@Param("eventId") UUID eventId);

    /**
     * Org-wide distinct-session counts per stage over a rolling window. Tuple
     * shape: {@code [String stage, Long distinctAnonCount]}. Generalizes
     * {@link #countDistinctAnonByStage(UUID)} from one event to all of an org's
     * active events by joining the funnel log to {@code Event} and filtering on
     * the event's owning org. Stages with zero rows are absent — the caller
     * defaults them to 0. Backs the org-wide Meta "signal health" funnel (spec §8).
     *
     * <p>Both bind params are non-null ({@code orgId}, {@code since}); no nullable
     * String is threaded through a SQL function, so this avoids the H2-vs-Postgres
     * {@code lower(bytea)} trap.
     */
    @Query("""
            select fe.stage, count(distinct fe.anonId) from FunnelEvent fe, Event ev
             where fe.eventId = ev.id
               and ev.orgId = :orgId
               and ev.deletedAt is null
               and fe.createdAt >= :since
             group by fe.stage
            """)
    List<Object[]> countDistinctAnonByStageForOrg(@Param("orgId") UUID orgId,
                                                   @Param("since") java.time.Instant since);

    /**
     * Visit count grouped by {@code utm_source} across all of an org's active
     * events. Tuple shape: {@code [String utmSource (nullable), Long visits]}.
     * A null source row is the "untagged" bucket. Joins the funnel log to
     * {@code Event} so org-scoping is enforced by the event's owner.
     */
    @Query("""
            select fe.utmSource, count(fe) from FunnelEvent fe, Event ev
             where fe.eventId = ev.id
               and ev.orgId = :orgId
               and ev.deletedAt is null
             group by fe.utmSource
            """)
    List<Object[]> countVisitsBySourceForOrg(@Param("orgId") UUID orgId);

    /**
     * As {@link #countVisitsBySourceForOrg(UUID)} but restricted to one non-web
     * client ({@code "ios"} / {@code "android"}).
     *
     * <p><b>Three queries rather than one with nullable parameters.</b> A
     * nullable {@code String} threaded into a comparison passes on H2 and 500s
     * on Postgres — the standing trap in this codebase — and the web case needs
     * a different predicate anyway. {@code AttributionService} picks; nothing
     * ever binds null here.
     */
    @Query("""
            select fe.utmSource, count(fe) from FunnelEvent fe, Event ev
             where fe.eventId = ev.id
               and ev.orgId = :orgId
               and ev.deletedAt is null
               and fe.client = :client
             group by fe.utmSource
            """)
    List<Object[]> countVisitsBySourceForOrgAndClient(@Param("orgId") UUID orgId,
                                                      @Param("client") String client);

    /**
     * The web slice.
     *
     * <p>NULL counts as web on purpose: every row written before V93 carries it
     * because no other kind of client existed. Reporting those as a separate
     * "unknown client" bucket would invent a distinction that was never made.
     */
    @Query("""
            select fe.utmSource, count(fe) from FunnelEvent fe, Event ev
             where fe.eventId = ev.id
               and ev.orgId = :orgId
               and ev.deletedAt is null
               and (fe.client is null or fe.client = 'web')
             group by fe.utmSource
            """)
    List<Object[]> countWebVisitsBySourceForOrg(@Param("orgId") UUID orgId);

    /**
     * Untagged (no {@code utm_source}) visits grouped by referrer host across an
     * org's active events. Tuple shape:
     * {@code [String referrerHost (nullable), Long visits]}. Ordered by visit
     * count desc so the caller can take the top N.
     */
    @Query("""
            select fe.referrerHost, count(fe) from FunnelEvent fe, Event ev
             where fe.eventId = ev.id
               and ev.orgId = :orgId
               and ev.deletedAt is null
               and fe.utmSource is null
             group by fe.referrerHost
             order by count(fe) desc
            """)
    List<Object[]> countUntaggedByReferrerHostForOrg(@Param("orgId") UUID orgId);

    /**
     * Distinct converting sessions attributed to a campaign: count of distinct
     * anon ids that hit CHECKOUT_START carrying this campaign's utm_campaign.
     * Visit-based proxy (orders carry no utm today — see AttributionService).
     */
    @Query("""
            select count(distinct fe.anonId) from FunnelEvent fe
             where fe.utmCampaign = :campaign
               and fe.stage = 'CHECKOUT_START'
            """)
    long countAttributedCheckoutSessions(@Param("campaign") String campaign);
}
