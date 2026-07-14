package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.ProviderEventRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Complaint-rate circuit breaker (spec §7). When a campaign's complaint rate
 * (complaints ÷ delivered) crosses ~0.1% above a minimum-volume floor, the
 * org's sending is auto-paused (organizations.marketing_paused_at set). The
 * dispatcher refuses to send while paused — one bad org can't burn the shared
 * news.imin.wtf reputation for everyone.
 */
@Service
public class ComplaintRateBreaker {

    private static final Logger log = LoggerFactory.getLogger(ComplaintRateBreaker.class);
    private static final double THRESHOLD = 0.001;   // 0.1%
    private static final long MIN_VOLUME = 200;      // never trip on tiny samples

    private final ProviderEventRepository providerEvents;
    private final OrganizationRepository orgs;

    public ComplaintRateBreaker(ProviderEventRepository providerEvents, OrganizationRepository orgs) {
        this.providerEvents = providerEvents;
        this.orgs = orgs;
    }

    @Transactional
    public void evaluate(UUID campaignId, UUID orgId) {
        if (campaignId == null || orgId == null) return;
        long delivered = providerEvents.countByCampaignIdAndType(campaignId, ProviderEvent.TYPE_DELIVERED);
        long complaints = providerEvents.countByCampaignIdAndType(campaignId, ProviderEvent.TYPE_COMPLAINED);
        long denom = Math.max(delivered, complaints); // guard: complaints can arrive before delivered counts settle
        if (denom < MIN_VOLUME) return;
        double rate = (double) complaints / denom;
        if (rate < THRESHOLD) return;

        Organization o = orgs.findById(orgId).orElse(null);
        if (o == null || o.getMarketingPausedAt() != null) return;
        o.setMarketingPausedAt(Instant.now());
        orgs.save(o);
        log.warn("[complaint-breaker] org={} PAUSED — campaign={} complaintRate={}", orgId, campaignId, rate);
    }
}
