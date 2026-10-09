package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    /** Ids bound per query; keeps the IN list far under Postgres's bind-parameter limit. */
    static final int MAX_IDS_PER_QUERY = 1000;

    private final CampaignRecipientRepository recipientRepo;
    private final MarketingGuardProperties props;

    public CampaignVolumeGuard(CampaignRecipientRepository recipientRepo,
                               MarketingGuardProperties props) {
        this.recipientRepo = recipientRepo;
        this.props = props;
    }

    /** The members contacted within the frequency floor before {@code now}. */
    @Transactional(readOnly = true)
    public Set<UUID> frequencyCapped(Collection<UUID> membershipIds, Instant now) {
        List<UUID> ids = membershipIds.stream().filter(Objects::nonNull).distinct().toList();
        Instant since = now.minus(props.getFrequencyFloorHours(), ChronoUnit.HOURS);
        Set<UUID> capped = new HashSet<>();
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_QUERY) {
            capped.addAll(recipientRepo.findRecentlySentMembershipIds(
                    ids.subList(from, Math.min(from + MAX_IDS_PER_QUERY, ids.size())), since));
        }
        return capped;
    }
}
