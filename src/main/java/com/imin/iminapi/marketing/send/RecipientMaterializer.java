package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.service.SendPathGuard;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientBulkInsert;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignVolumeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final CampaignRecipientBulkInsert bulkInsert;
    private final CampaignRepository campaigns;
    private final CampaignVolumeGuard volumeGuard;
    private final SendPathGuard sendPathGuard;

    public RecipientMaterializer(SegmentService segmentService, SendGateService sendGate,
                                 ConsumerRepository consumers,
                                 CampaignRecipientRepository recipients, CampaignRecipientBulkInsert bulkInsert,
                                 CampaignRepository campaigns,
                                 CampaignVolumeGuard volumeGuard, SendPathGuard sendPathGuard) {
        this.segmentService = segmentService;
        this.sendGate = sendGate;
        this.consumers = consumers;
        this.recipients = recipients;
        this.bulkInsert = bulkInsert;
        this.campaigns = campaigns;
        this.volumeGuard = volumeGuard;
        this.sendPathGuard = sendPathGuard;
    }

    /**
     * REQUIRES_NEW: the snapshot must be durable before the first batch leaves. Committing
     * it with the whole drive meant a mid-send crash deleted every row, so the automatic
     * re-claim re-materialised the FULL audience and re-emailed everyone already contacted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void materialize(Campaign c) {
        // Two drives of one campaign: the second waits here, then sees the first one's rows and skips.
        campaigns.lockForMaterialize(c.getId());
        if (recipients.countByCampaignId(c.getId()) > 0) {
            log.info("[materialize] campaign {} already has recipients — skipping", c.getId());
            return;
        }
        // A cancel that committed before this lock ran no skip over these rows: write them as already stopped.
        boolean canceled = "canceled".equals(campaigns.findStatusById(c.getId()).orElse(null));
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
        // Holdout, per-event and 30-day caps, and ConsentGate (plan campaigns, or all when flagged); checked again per batch.
        Map<UUID, String> guarded = sendPathGuard.skipReasons(c, gate.sendable(), now);
        // Per-member frequency floor (spec §7), asked only for members no guard skipped. Read before the
        // inserts: this snapshot writes only pending/skipped rows, which the floor does not count.
        Set<UUID> frequencyCapped = volumeGuard.frequencyCapped(
                gate.sendable().stream().filter(mid -> !guarded.containsKey(mid)).toList(), now);
        List<CampaignRecipientBulkInsert.Row> rows =
                new ArrayList<>(gate.sendable().size() + gate.excluded().size());
        int pending = 0;
        int sendableSkipped = 0;
        for (UUID mid : gate.sendable()) {
            String email = emailOf(byId.get(mid), emailByConsumer);
            String guardReason = guarded.get(mid);
            if (guardReason != null) {
                rows.add(new CampaignRecipientBulkInsert.Row(mid, email, "skipped", guardReason));
                summary.merge(guardReason, 1, Integer::sum);
                sendableSkipped++;
            } else if (frequencyCapped.contains(mid)) {
                rows.add(new CampaignRecipientBulkInsert.Row(mid, email, "skipped", "frequency_capped"));
                summary.merge("frequency_capped", 1, Integer::sum);
                sendableSkipped++;
            } else if (canceled) {
                // Still counted in recipientCount: the audience the campaign was stopped against.
                rows.add(new CampaignRecipientBulkInsert.Row(mid, email, "skipped",
                        CampaignRecipient.SKIP_CAMPAIGN_CANCELED));
                pending++;
            } else {
                rows.add(new CampaignRecipientBulkInsert.Row(mid, email, "pending", null));
                pending++;
            }
        }

        for (ExclusionReason ex : gate.excluded()) {
            String email = emailOf(byId.get(ex.membershipId()), emailByConsumer);
            rows.add(new CampaignRecipientBulkInsert.Row(ex.membershipId(), email, "skipped", ex.reason()));
            summary.merge(ex.reason(), 1, Integer::sum);
        }
        bulkInsert.insert(c.getId(), now, rows);

        c.setRecipientCount(pending);
        c.setExcludedCount(gate.excluded().size() + sendableSkipped);
        c.setExclusionSummary(toJson(summary));
        // Targeted: a full save of this drive's copy would write its status back over a concurrent cancel.
        campaigns.recordMaterialized(c.getId(), c.getRecipientCount(), c.getExcludedCount(), c.getExclusionSummary());
    }

    private static String emailOf(Membership m, Map<UUID, String> emailByConsumer) {
        return m == null ? null : emailByConsumer.get(m.getConsumerId());
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
