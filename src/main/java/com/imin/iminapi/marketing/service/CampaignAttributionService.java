package com.imin.iminapi.marketing.service;

import com.imin.iminapi.repository.FunnelEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Attributed-purchases read model for a campaign (spec §3). Counts distinct
 * checkout-start sessions carrying the campaign's utm_campaign tag written by
 * the imin-public /track beacon (V43). Visit-based proxy — orders carry no utm
 * key today (AttributionService.java:58-63). The FE tile labels this
 * "purchases attributed".
 */
@Service
public class CampaignAttributionService {

    private final FunnelEventRepository funnel;

    public CampaignAttributionService(FunnelEventRepository funnel) {
        this.funnel = funnel;
    }

    @Transactional(readOnly = true)
    public long attributedPurchaseCount(UUID campaignId) {
        if (campaignId == null) return 0;
        return funnel.countAttributedCheckoutSessions(campaignId.toString());
    }
}
