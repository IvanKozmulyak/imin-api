package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrows SendGate-sendable members to the ConsentGate-mailable ones while {@code consent-gate-all-campaigns}
 * is on, so counts made before a send (hub tile, Momentum floor) match who the send would actually reach.
 */
@Service
public class AllCampaignsConsent {

    private final AudiencePlanProperties props;
    private final ConsentGate consentGate;

    public AllCampaignsConsent(AudiencePlanProperties props, ConsentGate consentGate) {
        this.props = props;
        this.consentGate = consentGate;
    }

    public boolean enabled() {
        return Boolean.TRUE.equals(props.getConsentGateAllCampaigns());
    }

    /** Non-null input ids while the flag is off; else only the members ConsentGate finds mailable in this org. */
    public List<UUID> mailable(UUID orgId, Collection<UUID> sendGateSendable) {
        if (sendGateSendable == null) return List.of();
        List<UUID> ids = sendGateSendable.stream().filter(Objects::nonNull).toList();
        if (ids.isEmpty() || !enabled()) return ids;
        Map<UUID, Optional<String>> verdicts = consentGate.reasons(orgId, ids);
        // A member ConsentGate did not return (another org, or gone) is not mailable, as at send time.
        return ids.stream()
                .filter(id -> verdicts.getOrDefault(id, Optional.of(SendPathGuard.CONSENT_GATE)).isEmpty())
                .toList();
    }
}
