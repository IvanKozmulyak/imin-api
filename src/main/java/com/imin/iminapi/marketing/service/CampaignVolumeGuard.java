package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Per-org volume guardrails for the shared sending domain (spec §7):
 * per-membership frequency floor (a member contacted within the last N hours
 * is skipped with skip_reason='frequency_capped'). The per-org daily cap is
 * enforced by the dispatcher counting the org's rolling-24h sends against
 * {@link MarketingGuardProperties#getDailyCap()} before dispatching a batch.
 */
@Service
public class CampaignVolumeGuard {

    private final CampaignRecipientRepository recipientRepo;
    private final MarketingGuardProperties props;

    public CampaignVolumeGuard(CampaignRecipientRepository recipientRepo,
                               MarketingGuardProperties props) {
        this.recipientRepo = recipientRepo;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public boolean isFrequencyCapped(UUID membershipId, Instant now) {
        if (membershipId == null) return false;
        Instant since = now.minus(props.getFrequencyFloorHours(), ChronoUnit.HOURS);
        return recipientRepo.countRecentSendsForMembership(membershipId, since) > 0;
    }
}
