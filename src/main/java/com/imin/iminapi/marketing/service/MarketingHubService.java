package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.marketing.dto.MarketingHubMetricsDto;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-model behind GET /api/v1/marketing/hub-metrics — the numbers for the Marketing
 * hub tiles. Org comes ONLY from the AuthPrincipal (SPINE INVARIANT): every count is
 * scoped to {@code principal.orgId()}, so another org's members/suggestions/events can
 * never leak into a caller's totals.
 *
 * <p>Every field is REAL (from the DB / existing services) or a literal 0 when the source
 * genuinely does not exist — nothing is a placeholder.
 */
@Service
public class MarketingHubService {

    private static final Duration ATTRIBUTION_WINDOW = Duration.ofDays(30);

    private final MembershipRepository memberships;
    private final SendGateService sendGate;
    private final MomentumSuggestionRepository suggestions;
    private final EventRepository events;
    private final CampaignRepository campaigns;
    private final CampaignAttributionService attribution;
    private final MarketingEmailProperties emailProps;

    public MarketingHubService(MembershipRepository memberships,
                               SendGateService sendGate,
                               MomentumSuggestionRepository suggestions,
                               EventRepository events,
                               CampaignRepository campaigns,
                               CampaignAttributionService attribution,
                               MarketingEmailProperties emailProps) {
        this.memberships = memberships;
        this.sendGate = sendGate;
        this.suggestions = suggestions;
        this.events = events;
        this.campaigns = campaigns;
        this.attribution = attribution;
        this.emailProps = emailProps;
    }

    @Transactional(readOnly = true)
    public MarketingHubMetricsDto metrics(AuthPrincipal principal) {
        UUID orgId = principal.orgId();
        Instant now = Instant.now();

        // totalContacts: every membership in the org.
        int totalContacts = (int) memberships.countByOrgId(orgId);

        // sendableEmail: run the Send Gate over ALL the org's memberships — the same
        // machinery CampaignService.previewAudience uses, but for the full audience.
        List<UUID> allMemberIds = memberships.findAllMembershipIdsByOrgId(orgId);
        int sendableEmail = sendGate.evaluate(orgId, allMemberIds).sendable().size();

        // momentumWaiting: live 'suggested' suggestions for the org.
        int momentumWaiting = suggestions
                .findByOrgIdAndStatusOrderBySuggestedAtDesc(orgId, "suggested").size();

        // momentumWatching: on-sale events currently eligible for Momentum evaluation,
        // filtered to this org (findMomentumCandidates spans all orgs — MomentumEvaluator).
        int momentumWatching = 0;
        for (Event e : events.findMomentumCandidates(now)) {
            if (orgId.equals(e.getOrgId())) momentumWatching++;
        }

        // smsPhones: SMS opt-ins captured with a phone number.
        int smsPhones = (int) memberships.countSmsSubscribedByOrgId(orgId);

        // Attribution tiles, both scoped to the org's campaigns from the last 30 days.
        //  - attributedPurchases: distinct checkout-start SESSIONS carrying each campaign's
        //    utm_campaign (the /track funnel beacon).
        //  - attributedRevMinor: a TRUE per-order sum of the revenue those campaigns drove,
        //    joined on orders.utm_campaign = campaign id (V62). One batched query, not N.
        // The two come from different sources on purpose (sessions vs money that moved), so
        // they can legitimately disagree — see CampaignAttributionService.
        List<Campaign> recent = campaigns.findByOrgCreatedSince(orgId, now.minus(ATTRIBUTION_WINDOW));
        int attributedPurchases = 0;
        for (Campaign c : recent) {
            attributedPurchases += (int) attribution.attributedPurchaseCount(c.getId());
        }
        long attributedRevMinor = attribution
                .attributedRevenueMinorByCampaign(orgId, recent.stream().map(Campaign::getId).toList())
                .values().stream().mapToLong(Long::longValue).sum();

        // Marketing sender identity + whether it is configured enough to send.
        String emailFrom = emailProps.getFromAddress() == null ? "" : emailProps.getFromAddress();
        String emailFromName = emailProps.getFromName() == null ? "" : emailProps.getFromName();
        boolean sendingEnabled = !emailFrom.isBlank();

        return new MarketingHubMetricsDto(
                sendableEmail,
                totalContacts,
                attributedRevMinor,
                attributedPurchases,
                momentumWaiting,
                momentumWatching,
                smsPhones,
                emailFrom,
                emailFromName,
                sendingEnabled);
    }
}
