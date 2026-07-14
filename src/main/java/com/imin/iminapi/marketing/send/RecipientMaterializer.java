package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignVolumeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Spec §2.5 step 2: idempotently snapshots the channel-aware SendGate result into
 * campaign_recipients. Sendable → pending rows; excluded → skipped rows (skip_reason).
 * The per-reason aggregate is written to campaigns.exclusion_summary. Idempotent: if
 * recipient rows already exist for the campaign, it no-ops (crash-resume safe).
 */
@Service
public class RecipientMaterializer {

    private static final Logger log = LoggerFactory.getLogger(RecipientMaterializer.class);

    private final SegmentService segmentService;
    private final SendGateService sendGate;
    private final ConsumerRepository consumers;
    private final CampaignRecipientRepository recipients;
    private final CampaignRepository campaigns;
    private final CampaignVolumeGuard volumeGuard;

    public RecipientMaterializer(SegmentService segmentService, SendGateService sendGate,
                                 ConsumerRepository consumers,
                                 CampaignRecipientRepository recipients, CampaignRepository campaigns,
                                 CampaignVolumeGuard volumeGuard) {
        this.segmentService = segmentService;
        this.sendGate = sendGate;
        this.consumers = consumers;
        this.recipients = recipients;
        this.campaigns = campaigns;
        this.volumeGuard = volumeGuard;
    }

    @Transactional
    public void materialize(Campaign c) {
        if (recipients.countByCampaignId(c.getId()) > 0) {
            log.info("[materialize] campaign {} already has recipients — skipping", c.getId());
            return;
        }
        // Resolve the segment to its loaded memberships via the REAL SegmentService surface:
        // requireSegmentForOrg(orgId, segmentId) -> Segment (leak-safe 404 on cross-org),
        // then resolveMembers(orgId, segment) -> List<Membership>. NO resolveMembershipIds exists.
        Segment segment = segmentService.requireSegmentForOrg(c.getOrgId(), c.getSegmentId());
        List<Membership> candidates = segmentService.resolveMembers(c.getOrgId(), segment);
        List<UUID> candidateIds = candidates.stream().map(Membership::getMembershipId).toList();
        SendGateService.GateResult gate = sendGate.evaluate(c.getOrgId(), candidateIds);

        // resolveMembers already returned loaded entities — build the lookup directly, no re-fetch.
        Map<UUID, Membership> byId = new HashMap<>();
        candidates.forEach(m -> byId.put(m.getMembershipId(), m));
        Map<UUID, String> emailByConsumer = new HashMap<>();
        consumers.findAllByConsumerIdIn(byId.values().stream().map(Membership::getConsumerId).distinct().toList())
                .forEach(cn -> emailByConsumer.put(cn.getConsumerId(), cn.getNormalizedEmail()));

        Map<String, Integer> summary = new TreeMap<>();
        Instant now = Instant.now();
        int pending = 0;
        int frequencySkipped = 0;
        for (UUID mid : gate.sendable()) {
            Membership m = byId.get(mid);
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setMembershipId(mid);
            r.setEmail(m == null ? null : emailByConsumer.get(m.getConsumerId()));
            r.setLastEventAt(now);
            // Per-member frequency floor (spec §7): a member contacted within the floor
            // window is diverted to a skipped row rather than sent again.
            if (volumeGuard.isFrequencyCapped(mid, now)) {
                r.setStatus("skipped");
                r.setSkipReason("frequency_capped");
                summary.merge("frequency_capped", 1, Integer::sum);
                frequencySkipped++;
            } else {
                r.setStatus("pending");
                pending++;
            }
            recipients.save(r);
        }

        for (ExclusionReason ex : gate.excluded()) {
            Membership m = byId.get(ex.membershipId());
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setMembershipId(ex.membershipId());
            r.setEmail(m == null ? null : emailByConsumer.get(m.getConsumerId()));
            r.setStatus("skipped");
            r.setSkipReason(ex.reason());
            r.setLastEventAt(now);
            recipients.save(r);
            summary.merge(ex.reason(), 1, Integer::sum);
        }

        c.setRecipientCount(pending);
        c.setExcludedCount(gate.excluded().size() + frequencySkipped);
        c.setExclusionSummary(toJson(summary));
        campaigns.save(c);
    }

    private static String toJson(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }
}
