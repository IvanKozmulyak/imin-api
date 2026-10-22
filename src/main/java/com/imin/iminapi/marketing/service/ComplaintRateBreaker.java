package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.MarketingChannelStatsRepository;
import com.imin.iminapi.marketing.repository.ProviderEventRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Complaint and bounce circuit breaker (spec §7). Pauses the org's marketing sending (organizations.marketing_paused_at
 * with a reason) when complaints ÷ delivered reach 0.1% on one campaign or across the org's last 30 days, or hard
 * bounces ÷ (delivered + bounced) reach 5% across those 30 days, each above a 200-email floor. Only events after the
 * last staff resume count, so a resumed org is judged on later mail. While paused no batch of the org starts; only
 * IMIN staff lift it, because the pause protects the sending domain every organizer shares.
 */
@Service
public class ComplaintRateBreaker {

    private static final Logger log = LoggerFactory.getLogger(ComplaintRateBreaker.class);
    /** Complaint rate (complaints ÷ delivered) at or above which an org is auto-paused. 0.1%. */
    public static final double THRESHOLD = 0.001;
    /** Hard-bounce rate (hard bounces ÷ (delivered + bounced)) at or above which an org is auto-paused. 5%. */
    public static final double BOUNCE_THRESHOLD = 0.05;
    /** Never trip on tiny samples: below this volume (one campaign, or the org window) the breaker does not evaluate. */
    public static final long MIN_VOLUME = 200;
    /** Length of the org-wide window, in days. */
    public static final int ORG_WINDOW_DAYS = 30;
    public static final String REASON_COMPLAINT_RATE = "complaint_rate";
    public static final String REASON_BOUNCE_RATE = "bounce_rate";

    private final ProviderEventRepository providerEvents;
    private final MarketingChannelStatsRepository orgEvents;
    private final CampaignRecipientRepository recipients;
    private final OrganizationRepository orgs;
    private final AuditLogger audit;

    public ComplaintRateBreaker(ProviderEventRepository providerEvents, MarketingChannelStatsRepository orgEvents,
                                CampaignRecipientRepository recipients, OrganizationRepository orgs,
                                AuditLogger audit) {
        this.providerEvents = providerEvents;
        this.orgEvents = orgEvents;
        this.recipients = recipients;
        this.orgs = orgs;
        this.audit = audit;
    }

    /** Start of the org window: 30 days back, or the last resume when that is later. */
    public static Instant orgWindowStart(Instant now, Instant resumedAt) {
        Instant start = now.minus(ORG_WINDOW_DAYS, ChronoUnit.DAYS);
        return resumedAt != null && resumedAt.isAfter(start) ? resumedAt : start;
    }

    /** Complaints over the floor at or above the threshold; complaints can arrive before delivered counts settle. */
    public static boolean complaintRateTrips(long delivered, long complaints) {
        long denom = Math.max(delivered, complaints);
        return denom >= MIN_VOLUME && (double) complaints / denom >= THRESHOLD;
    }

    public static boolean bounceRateTrips(long delivered, long bounced, long hardBounces) {
        long denom = delivered + bounced;
        return denom >= MIN_VOLUME && (double) hardBounces / denom >= BOUNCE_THRESHOLD;
    }

    @Transactional
    public void evaluate(UUID campaignId, UUID orgId) {
        if (orgId == null) return;
        Organization o = orgs.findById(orgId).orElse(null);
        if (o == null || o.getMarketingPausedAt() != null) return;
        Instant now = Instant.now();
        Instant resumedAt = o.getMarketingResumedAt();
        String reason = null;
        String detail = null;
        if (campaignId != null) {
            Instant since = resumedAt == null ? Instant.EPOCH : resumedAt;
            long delivered = providerEvents.countByCampaignIdAndTypeSince(campaignId, ProviderEvent.TYPE_DELIVERED, since);
            long complaints = providerEvents.countByCampaignIdAndTypeSince(campaignId, ProviderEvent.TYPE_COMPLAINED, since);
            if (complaintRateTrips(delivered, complaints)) {
                reason = REASON_COMPLAINT_RATE;
                detail = "campaign " + campaignId + ": " + complaints + " complaints, " + delivered + " delivered";
            }
        }
        if (reason == null) {
            Instant since = orgWindowStart(now, resumedAt);
            long delivered = orgEvents.countByOrgIdAndTypeSince(orgId, ProviderEvent.TYPE_DELIVERED, since);
            long complaints = orgEvents.countByOrgIdAndTypeSince(orgId, ProviderEvent.TYPE_COMPLAINED, since);
            long bounced = orgEvents.countByOrgIdAndTypeSince(orgId, ProviderEvent.TYPE_BOUNCED, since);
            long hard = recipients.countHardBouncesForOrgSince(orgId, since);
            if (complaintRateTrips(delivered, complaints)) {
                reason = REASON_COMPLAINT_RATE;
                detail = "org window: " + complaints + " complaints, " + delivered + " delivered";
            } else if (bounceRateTrips(delivered, bounced, hard)) {
                reason = REASON_BOUNCE_RATE;
                detail = "org window: " + hard + " hard bounces, " + delivered + " delivered, " + bounced + " bounced";
            }
        }
        if (reason == null) return;
        // Conditional: a concurrent trip already paused it, and only one of them records the pause.
        if (orgs.pauseMarketingIfNotPaused(orgId, reason, now.truncatedTo(ChronoUnit.MICROS)) == 0) return;
        log.error("[complaint-breaker] org={} marketing sending PAUSED ({}) — {}", orgId, reason, detail);
        audit.record(new AuthPrincipal(null, orgId, UserRole.MEMBER, null), AuditActions.MARKETING_SENDING_PAUSED,
                "organization", orgId, "Marketing sending paused: " + reason + " (" + detail + ")");
    }
}
