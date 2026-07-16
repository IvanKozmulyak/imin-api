package com.imin.iminapi.marketing.service;

import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Attribution read models for a campaign (spec §3), keyed by the campaign's
 * {@code utm_campaign} tag — the campaign UUID as a string, written into every campaign
 * link by {@code UtmLinkRewriter} (via {@code EmailChannelSender}, which passes
 * {@code campaign.getId().toString()}).
 *
 * <p>Two DIFFERENT sources, deliberately:
 * <ul>
 *   <li>{@link #attributedPurchaseCount} counts distinct checkout-start SESSIONS from the
 *       imin-public {@code /track} beacon (V43). It is a visit-based proxy — a session that
 *       starts checkout but never pays still counts. The FE tile labels it
 *       "purchases attributed".</li>
 *   <li>{@link #attributedRevenueMinor} sums real ORDER revenue by {@code orders.utm_campaign}
 *       (V62) — a true per-order last-touch sum, not an estimate. Only money that actually
 *       moved is counted.</li>
 * </ul>
 * The two can legitimately disagree (sessions ≥ paid orders); they measure different things
 * and neither is derived from the other.
 */
@Service
public class CampaignAttributionService {

    private final FunnelEventRepository funnel;
    private final OrderRepository orders;

    public CampaignAttributionService(FunnelEventRepository funnel, OrderRepository orders) {
        this.funnel = funnel;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public long attributedPurchaseCount(UUID campaignId) {
        if (campaignId == null) return 0;
        return funnel.countAttributedCheckoutSessions(campaignId.toString());
    }

    /**
     * Real attributed revenue (minor units) for ONE campaign: the sum of the org's orders
     * whose {@code utm_campaign} is this campaign's id. Lifetime, not windowed.
     *
     * <p>Returns 0 — honestly — when the campaign drove no paid orders, and for every
     * campaign that ran before V62: those orders carry no {@code utm_campaign} and can
     * never be back-filled, because the attribution was never captured at checkout.
     */
    @Transactional(readOnly = true)
    public long attributedRevenueMinor(UUID orgId, UUID campaignId) {
        if (orgId == null || campaignId == null) return 0;
        return orders.sumTotalMinorByOrgIdAndUtmCampaign(orgId, campaignId.toString());
    }

    /**
     * Batched {@link #attributedRevenueMinor} — ONE query for many campaigns so the campaign
     * list doesn't N+1. Campaigns with no attributed revenue map to 0 rather than being
     * omitted, so callers can read every id back without a null check.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> attributedRevenueMinorByCampaign(UUID orgId, Collection<UUID> campaignIds) {
        Map<UUID, Long> out = new HashMap<>();
        if (orgId == null || campaignIds == null) return out;
        for (UUID id : campaignIds) {
            if (id != null) out.put(id, 0L);
        }
        // IN () is invalid SQL — skip the round-trip entirely when there is nothing to ask for.
        if (out.isEmpty()) return out;

        List<String> keys = out.keySet().stream().map(UUID::toString).toList();
        for (Object[] row : orders.sumRevenueByUtmCampaignIn(orgId, keys)) {
            String key = (String) row[0];
            long revenue = ((Number) row[1]).longValue();
            try {
                out.put(UUID.fromString(key), revenue);
            } catch (IllegalArgumentException ignored) {
                // utm_campaign is buyer-supplied and free-form — a hand-typed or third-party
                // link can carry any string. Only values that parse back to a campaign id we
                // asked for are counted; anything else is ignored rather than crashing the read.
            }
        }
        return out;
    }
}
